package org.hyperledger.identus.walletsdk.pollux.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.hyperledger.identus.walletsdk.domain.models.Credential

/**
 * The `CredentialRequest` interface represents a request for verifiable credentials.
 *
 * @property cred_def_id The ID of the credential definition.
 * @property blinded_ms The blinded master secret for the credential request.
 * @property blinded_ms_correctness_proof The correctness proof for the blinded master secret.
 * @property entropy The entropy for the credential request.
 * @property nonce The nonce for the credential request.
 */
@Suppress("ktlint:standard:property-naming")
interface CredentialRequest {
    val cred_def_id: String
    val blinded_ms: CredentialRequestBlindedMS
    val blinded_ms_correctness_proof: CredentialRequestBlindedMSCorrectnessProof
    val entropy: String
    val nonce: String
}

/**
 * Represents the blinded master secret used in the credential request.
 */
interface CredentialRequestBlindedMS {
    val u: String
    val ur: String
}

/**
 * This interface represents a blinded master secret correctness proof for a credential request.
 *
 * @property c The blinded master secret contribution to the proof.
 * @property v_dash_cap The value used in the proof calculation.
 * @property m_caps The map of attribute names to their corresponding blinded value commitments.
 */
@Suppress("ktlint:standard:property-naming")
interface CredentialRequestBlindedMSCorrectnessProof {
    val c: String
    val v_dash_cap: String
    val m_caps: Map<String, String>
}

/**
 * Represents the blinding data used in the Link-Secret protocol.
 * This class is serialized and deserialized using Kotlinx Serialization library.
 *
 * @param vPrime The blinding factor generated by the Holder and sent to the Issuer.
 * @param vrPrime The blinded master secret generated by the Holder. Default value is null.
 */
@Serializable
data class LinkSecretBlindingData
@OptIn(ExperimentalSerializationApi::class)
constructor(
    @SerialName("v_prime")
    var vPrime: String,
    @SerialName("vr_prime")
    @EncodeDefault
    var vrPrime: String? = null
)

/**
 * Represents a credential definition.
 *
 * @property schemaId The unique identifier of the schema associated with the credential definition.
 * @property type The type of the credential definition.
 * @property tag The tag associated with the credential definition.
 * @property value An array of values associated with the credential definition.
 * @property issuerId The unique identifier of the issuer of the credential definition.
 */
data class CredentialDefinition(
    val schemaId: String,
    val type: String,
    val tag: String,
    val value: Array<String>,
    val issuerId: String
) {
    /**
     * Checks if this CredentialDefinition object is equal to the specified object.
     *
     * Two CredentialDefinition objects are considered equal if they have the same values for the following properties:
     * - [schemaId]
     * - [type]
     * - [tag]
     * - [value]
     * - [issuerId]
     *
     * @param other The object to compare with this CredentialDefinition object.
     * @return true if the specified object is equal to this CredentialDefinition object, false otherwise.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CredentialDefinition

        if (schemaId != other.schemaId) return false
        if (type != other.type) return false
        if (tag != other.tag) return false
        if (!value.contentEquals(other.value)) return false
        if (issuerId != other.issuerId) return false

        return true
    }

    /**
     * Calculates the hash code of the [CredentialDefinition] object.
     *
     * The hash code is computed based on the values of the following properties:
     * - [schemaId]
     * - [type]
     * - [tag]
     * - [value]
     * - [issuerId]
     *
     * @return The hash code of the [CredentialDefinition] object.
     */
    override fun hashCode(): Int {
        var result = schemaId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + tag.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + issuerId.hashCode()
        return result
    }
}

/**
 * Represents an interface for a verifiable credential that has been issued.
 *
 * Implementing classes are expected to provide implementation for all properties
 * and functions defined in this interface.
 */
interface CredentialIssued : Credential {
    val values: List<Pair<String, CredentialValue>>
}

/**
 * Represents a credential value, which consists of an encoded value and its raw value.
 *
 * Implementing classes are expected to provide implementation for both [encoded] and [raw] properties.
 */
interface CredentialValue {
    val encoded: String
    val raw: String
}