package com.ewc.eudi_wallet_oidc_android.services.issue

import android.net.Uri
import com.ewc.eudi_wallet_oidc_android.models.AuthorizationDetails
import com.ewc.eudi_wallet_oidc_android.models.ClientMetaData
import com.ewc.eudi_wallet_oidc_android.models.CredentialOffer
import com.ewc.eudi_wallet_oidc_android.models.CredentialRequest
import com.ewc.eudi_wallet_oidc_android.models.CredentialResponse
import com.ewc.eudi_wallet_oidc_android.models.Jwt
import com.ewc.eudi_wallet_oidc_android.models.ProofV3
import com.ewc.eudi_wallet_oidc_android.models.TokenResponse
import com.ewc.eudi_wallet_oidc_android.models.VpFormatsSupported
import com.ewc.eudi_wallet_oidc_android.services.codeVerifier.CodeVerifierService
import com.ewc.eudi_wallet_oidc_android.services.network.ApiManager
import com.google.gson.Gson
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

class IssueService : IssueServiceInterface {

    /**
     * To process the credential offer request
     * @param data - will accept the full data which is scanned from the QR code or deep link
     *                  The data can contain credential offer or credential offer uri
     * @return Credential Offer
     */
    override suspend fun resolveCredentialOffer(data: String?): CredentialOffer? {
        if (data.isNullOrBlank()) return null

        val uri = Uri.parse(data)
        val credentialOfferUri = uri.getQueryParameter("credential_offer_uri")
        if (!credentialOfferUri.isNullOrBlank()) {
            val response = ApiManager.api.getService()?.resolveCredentialOffer(credentialOfferUri)
            return if (response?.isSuccessful == true) {
                response.body()
            } else {
                null
            }
        }

        val credentialOfferString = uri.getQueryParameter("credential_offer")
        if (!credentialOfferString.isNullOrBlank()) {
            return Gson().fromJson(credentialOfferString, CredentialOffer::class.java)
        }
        return null
    }


    /**
     * To process the authorisation request
     * The authorisation request is to grant access to the credential endpoint
     * @param did - DID created for the issuance
     * @param subJwk - for singing the requests
     * @param credentialOffer - To build the authorisation request
     * @param codeVerifier - to build the authorisation request
     * @param authorisationEndPoint - to build the authorisation request
     *
     * @return String - short-lived authorisation code
     */
    override suspend fun processAuthorisationRequest(
        did: String?,
        subJwk: ECKey?,
        credentialOffer: CredentialOffer?,
        codeVerifier: String,
        authorisationEndPoint: String?
    ): String? {
        val responseType = "code"
        val scope = "openid"
        val state = UUID.randomUUID().toString()
        val clientId = did
        val authorisationDetails = Gson().toJson(
            arrayListOf(
                AuthorizationDetails(
                    types = credentialOffer?.credentials?.get(0)?.types,
                    locations = arrayListOf(credentialOffer?.credentialIssuer ?: "")
                )
            )
        )

        val redirectUri = "http://localhost:8080"
        val nonce = UUID.randomUUID().toString()

        val codeChallenge = CodeVerifierService().generateCodeChallenge(codeVerifier)
        val codeChallengeMethod = "S256"
        val clientMetadata = Gson().toJson(
            ClientMetaData(
                vpFormatsSupported = VpFormatsSupported(
                    jwtVp = Jwt(arrayListOf("ES256")), jwtVc = Jwt(arrayListOf("ES256"))
                ), responseTypesSupported = arrayListOf(
                    "vp_token", "id_token"
                ), authorizationEndpoint = redirectUri
            )
        )

        val response = ApiManager.api.getService()?.processAuthorisationRequest(
            authorisationEndPoint ?: "",
            mapOf(
                "response_type" to responseType,
                "scope" to scope,
                "state" to state,
                "client_id" to (clientId ?: ""),
                "authorization_details" to authorisationDetails,
                "redirect_uri" to redirectUri,
                "nonce" to nonce,
                "code_challenge" to (codeChallenge ?: ""),
                "code_challenge_method" to codeChallengeMethod,
                "client_metadata" to clientMetadata,
                "issuer_state" to (credentialOffer?.grants?.authorizationCode?.issuerState ?: "")
            ),
        )

        val location: String? = if (response?.code() == 302) {
            response.headers()["Location"]
        } else {
            null
        }

        return if (Uri.parse(location).getQueryParameter("code") != null) {
            location
        } else {
            processAuthorisationRequestUsingIdToken(
                did = did,
                authorisationEndPoint = authorisationEndPoint,
                location = location,
                subJwk = subJwk
            )
        }
    }

    private suspend fun processAuthorisationRequestUsingIdToken(
        did: String?,
        authorisationEndPoint: String?,
        location: String?,
        subJwk: ECKey?
    ): String? {
        val claimsSet =
            JWTClaimsSet.Builder()
                .issueTime(Date())
                .expirationTime(Date(Date().time + 60000))
                .issuer(did)
                .subject(did)
                .audience(authorisationEndPoint)
                .claim("nonce", Uri.parse(location).getQueryParameter("nonce"))
                .build()

        // Create JWT for ES256K alg
        val jwsHeader = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT)
            .keyID("$did#${did?.replace("did:key:", "")}").jwk(subJwk?.toPublicJWK()).build()

        val jwt = SignedJWT(
            jwsHeader, claimsSet
        )

        // Sign with private EC key
        jwt.sign(ECDSASigner(subJwk))

        val response = ApiManager.api.getService()?.sendIdTokenForCode(
            url = Uri.parse(location).getQueryParameter("redirect_uri") ?: "",
            idToken = jwt.serialize(),
            state = Uri.parse(location).getQueryParameter("state") ?: "",
            contentType = "application/x-www-form-urlencoded"
        )

        return if (response?.code() == 302) {
            response.headers()["Location"]
        } else {
            null
        }
    }

    /**
     * To process the token,
     *
     * @param did
     * @param tokenEndPoint
     * @param code - If the credential offer is pre authorised, then use the pre authorised code from the credential offer
     *              else use the code from the previous function - processAuthorisationRequest
     * @param codeVerifier - use the same code verifier used for processAuthorisationRequest
     * @param isPreAuthorisedCodeFlow - boolean value to notify its a pre authorised request
     *                                  if pre-authorized_code is present
     * @param userPin - optional value, if the user_pin_required is true
     *              PIN will be provided by the user
     *
     * @return Token response
     */
    override suspend fun processTokenRequest(
        did: String?,
        tokenEndPoint: String?,
        code: String?,
        codeVerifier: String?,
        isPreAuthorisedCodeFlow: Boolean?,
        userPin: String?
    ): TokenResponse? {
        val response = ApiManager.api.getService()?.getAccessTokenFromCode(
            tokenEndPoint ?: "",
            if (isPreAuthorisedCodeFlow == true) mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:pre-authorized_code",
                "pre-authorized_code" to (code ?: ""),
                "user_pin" to (userPin?:"")
            )
            else mapOf(
                "grant_type" to "authorization_code",
                "code" to (code ?: ""),
                "client_id" to (did ?: ""),
                "code_verifier" to (codeVerifier ?: "")
            ),
        )

        return if (response?.isSuccessful == true) {
            response.body()
        } else if ((response?.code() ?: 400) >= 400) {
            try {
                val jObjError = JSONObject(response?.errorBody()!!.string())
                if (jObjError.has("error_description")) {
                    TokenResponse(
                        error = jObjError.getString("error"),
                        errorDescription = jObjError.getString("error_description")
                    )
                } else if (jObjError.has("errors")) {
                    val errorList = JSONArray(jObjError.getString("errors"))
                    TokenResponse(
                        error = "Error",
                        errorDescription = errorList.getJSONObject(0).getString("message")
                    )
                } else if (jObjError.has("error")) {
                    TokenResponse(
                        error = jObjError.getString("error"),
                        errorDescription = jObjError.getString("error")
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * To process the credential, credentials can be issued in two ways,
     *     intime and deferred
     *
     *     If its intime, then we will receive the credential as the response
     *     If its deferred, then we will get he acceptance token and use this acceptance token to call deferred
     *
     * @param did
     * @param subJwk
     * @param credentialIssuerUrl
     * @param nonce
     * @param credentialOffer
     * @param credentialIssuerEndPoint
     * @param accessToken
     *
     * @return credential response
     */
    override suspend fun processCredentialRequest(
        did: String?,
        subJwk: ECKey?,
        credentialIssuerUrl: String?,
        nonce: String?,
        credentialOffer: CredentialOffer?,
        credentialIssuerEndPoint: String?,
        accessToken: String?
    ): CredentialResponse? {
        val claimsSet =
            JWTClaimsSet.Builder().issueTime(Date()).expirationTime(Date(Date().time + 86400))
                .issuer(did).audience(credentialIssuerUrl).claim("nonce", nonce).build()

        // Create JWT for ES256K alg
        val jwsHeader =
            JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("openid4vci-proof+jwt"))
                .keyID("$did#${did?.replace("did:key:", "")}").jwk(subJwk?.toPublicJWK()).build()

        val jwt = SignedJWT(
            jwsHeader, claimsSet
        )

        // Sign with private EC key
        jwt.sign(ECDSASigner(subJwk))

        val body = CredentialRequest(
            types = credentialOffer?.credentials?.get(0)?.types,
            format = credentialOffer?.credentials?.get(0)?.format,
            ProofV3(
                proofType = "jwt",
                jwt = jwt.serialize()
            )
        )
        val response = ApiManager.api.getService()?.getCredential(
            credentialIssuerEndPoint ?: "",
            "application/json",
            "Bearer $accessToken",
            body
        )

        return if (response?.isSuccessful == true) {
            response.body()
        } else if ((response?.code() ?: 0) >= 400) {
            try {
                val jObjError = JSONObject(response?.errorBody()!!.string())
                if (response.errorBody()!!.string()
                        .contains(
                            "Invalid Proof JWT: iss doesn't match the expected client_id",
                            true
                        )
                ) {
                    CredentialResponse(error = 1)
                } else if (jObjError.has("error_description")) {
                    CredentialResponse(
                        error = -1,
                        errorDescription = jObjError.getString("error_description")
                    )
                } else if (jObjError.has("errors")) {
                    val errorList = JSONArray(jObjError.getString("errors"))
                    CredentialResponse(
                        error = -1,
                        errorDescription = errorList.getJSONObject(0).getString("message")
                    )
                } else if (jObjError.has("error")) {
                    CredentialResponse(
                        error = -1,
                        errorDescription = jObjError.getString("error")
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            null
        } else {
            null
        }
    }

    /**
     * For issuance of the deferred credential.
     * @param acceptanceToken - token which we got from credential request
     * @param deferredCredentialEndPoint - end point to call the deferred credential
     *
     * @return Credential response
     */
    override suspend fun processDeferredCredentialRequest(
        acceptanceToken: String?,
        deferredCredentialEndPoint: String?
    ): CredentialResponse? {
        val response = ApiManager.api.getService()?.getDifferedCredential(
            deferredCredentialEndPoint ?: "",
            "Bearer $acceptanceToken",
            CredentialRequest() //empty object
        )

        return if (response?.isSuccessful == true
            && response.body()?.credential != null
        ) {
            response.body()
        } else {
            null
        }
    }


}