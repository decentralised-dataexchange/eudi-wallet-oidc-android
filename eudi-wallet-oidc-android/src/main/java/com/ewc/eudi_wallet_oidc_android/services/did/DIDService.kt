package com.ewc.eudi_wallet_oidc_android.services.did

import com.mediaparkpk.base58android.Base58
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator
import com.nimbusds.jose.util.Base64URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID


class DIDService : DIDServiceInterface {

    /**
     * Generate a did:key:jcs-pub decentralised identifier.
     * @param jwk - DID is created using the JWK
     * @return DID
     */
    override fun createDID(jwk: ECKey): String {
        val publicKey = jwk.toPublicJWK()

        val compactJson =
            "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${publicKey?.x}\",\"y\":\"${publicKey?.y}\"}"

        // UTF-8 encode the string
        val encodedBytes: ByteArray? = compactJson.toByteArray(StandardCharsets.UTF_8)

        // Add multiCodec byte
        val multiCodecBytes = addMultiCodecByte(encodedBytes)

        // Apply multiBase base58-btc encoding
        val multiBaseEncoded = multiBaseEncode(multiCodecBytes!!)

        // Prefix the string with "did:key"
        return "did:key:z$multiBaseEncoded"
    }

    /**
     * Generate JWK of curve P-256 for an optional seed value. (ECKey)
     * @param seed is optional, if seed is present then the JWK will be created with the seed
     *          if seed is not present, then will create a new JWK
     *
     * @return JWK
     */
    override fun createJWK(seed: String?): ECKey {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        if (seed != null) {
            val seedBytes = seed.toByteArray(StandardCharsets.UTF_8)
            keyPairGenerator.initialize(256, SecureRandom(seedBytes))
        } else {
            keyPairGenerator.initialize(256)
        }
        val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

        val publicKey = convertToECPublicKey(keyPair.public)
        val privateKey = convertToECPrivateKey(keyPair.private)

        val ecKey = ECKey.Builder(Curve.P_256, publicKey)
            .privateKey(privateKey).build()

        return ecKey
    }

    /**
     * Generate JWK of curve Ed25519
     *
     *  @return JWK
     */
    override fun createED25519JWK(): OctetKeyPair? {
        val jwk = OctetKeyPairGenerator(Curve.Ed25519)
            .keyID(UUID.randomUUID().toString())
            .generate()

        return jwk
    }

    /**
     * Generate DID for the ED25519
     * @param privateKeyX - X value of the ED25519 jwk
     *
     * @return DID
     */
    override fun createDidED25519(privateKeyX: Base64URL): String {
        val startArray = byteArrayOf(0xed.toByte(), 0x01)
        val newArray = startArray + Base64URL(privateKeyX.toString()).decode()
        // 3. base58 encode the prefixed public key bytes.
        var encoded = Base58.encode(newArray)
        // 4. prefix the output with ‘z’
        encoded = "did:key:z$encoded"
        return encoded
    }

    /**
     * Convert the PrivateKey to ECPrivateKey
     * @param privateKey
     *
     * @return ECPrivateKey
     */
    private fun convertToECPrivateKey(privateKey: PrivateKey): ECPrivateKey? {
        return if (privateKey is ECPrivateKey) {
            // If the PrivateKey is already an ECPrivateKey, simply cast and return it
            privateKey
        } else try {
            val privateKeyBytes = privateKey.encoded
            val keySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            var keyFactory = KeyFactory.getInstance("EC")
            val ecPrivateKey = keyFactory.generatePrivate(keySpec) as ECPrivateKey

            // Get EC parameters (curve) from the ECPrivateKey
            val params: java.security.spec.ECParameterSpec = ecPrivateKey.params

            // Set the EC parameters on the ECPrivateKey (required for some operations)
            val privateKeySpec = java.security.spec.ECPrivateKeySpec(ecPrivateKey.s, params)
            keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePrivate(privateKeySpec) as ECPrivateKey
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Convert the PublicKey to ECPublicKey
     * @param publicKey
     *
     * @return ECPublicKey
     */
    private fun convertToECPublicKey(publicKey: PublicKey): ECPublicKey? {
        return if (publicKey is ECPublicKey) {
            // If the PublicKey is already an ECPublicKey, simply cast and return it
            publicKey
        } else try {
            val w = (publicKey as ECPublicKey).w
            val params = (publicKey as java.security.interfaces.ECKey).params
            val spec = ECPublicKeySpec(w, params)
            val kf = KeyFactory.getInstance("EC")
            kf.generatePublic(spec) as ECPublicKey
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun addMultiCodecByte(input: ByteArray?): ByteArray? {
        val multiCodecPrefix = byteArrayOf(0xd1.toByte(), 0xd6.toByte(), 0x03)
        return multiCodecPrefix.plus(input!!)
    }

    private fun multiBaseEncode(input: ByteArray): String? {
        return Base58.encode(input)
    }
}