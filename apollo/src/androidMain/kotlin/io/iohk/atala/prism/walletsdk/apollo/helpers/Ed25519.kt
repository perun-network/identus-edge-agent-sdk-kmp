package io.iohk.atala.prism.walletsdk.apollo.helpers

import io.iohk.atala.prism.walletsdk.domain.models.Curve
import io.iohk.atala.prism.walletsdk.domain.models.KeyCurve
import io.iohk.atala.prism.walletsdk.domain.models.KeyPair
import io.iohk.atala.prism.walletsdk.domain.models.PrivateKey
import io.iohk.atala.prism.walletsdk.domain.models.PublicKey
import java.security.KeyPairGenerator
import java.security.KeyPair as JavaKeyPair

/**
 * Ed25519 is a variation of EdDSA
 */
actual object Ed25519 {
    actual fun createKeyPair(): KeyPair {
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val javaKeyPair: JavaKeyPair = kpg.generateKeyPair()
        return KeyPair(
            KeyCurve(Curve.ED25519),
            PrivateKey(
                KeyCurve(Curve.ED25519),
                javaKeyPair.private.encoded
            ),
            PublicKey(
                KeyCurve(Curve.ED25519),
                javaKeyPair.public.encoded
            )
        )
    }
}