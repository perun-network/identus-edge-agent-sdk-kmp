package org.hyperledger.identus.walletsdk.pollux

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.HttpStatusCode
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.hyperledger.identus.apollo.derivation.MnemonicHelper
import org.hyperledger.identus.walletsdk.apollo.ApolloImpl
import org.hyperledger.identus.walletsdk.apollo.utils.Ed25519KeyPair
import org.hyperledger.identus.walletsdk.apollo.utils.Secp256k1KeyPair
import org.hyperledger.identus.walletsdk.castor.CastorImpl
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Castor
import org.hyperledger.identus.walletsdk.domain.models.CredentialType
import org.hyperledger.identus.walletsdk.domain.models.Curve
import org.hyperledger.identus.walletsdk.domain.models.DID
import org.hyperledger.identus.walletsdk.domain.models.InputFieldFilter
import org.hyperledger.identus.walletsdk.domain.models.JWTVerifiableCredential
import org.hyperledger.identus.walletsdk.domain.models.KeyCurve
import org.hyperledger.identus.walletsdk.domain.models.PolluxError
import org.hyperledger.identus.walletsdk.domain.models.PresentationClaims
import org.hyperledger.identus.walletsdk.domain.models.Seed
import org.hyperledger.identus.walletsdk.domain.models.keyManagement.PrivateKey
import org.hyperledger.identus.walletsdk.edgeagent.protocols.proofOfPresentation.PresentationDefinitionRequest
import org.hyperledger.identus.walletsdk.edgeagent.protocols.proofOfPresentation.PresentationOptions
import org.hyperledger.identus.walletsdk.edgeagent.protocols.proofOfPresentation.PresentationSubmission
import org.hyperledger.identus.walletsdk.edgeagent.protocols.proofOfPresentation.PresentationSubmissionOptionsJWT
import org.hyperledger.identus.walletsdk.logger.PrismLogger
import org.hyperledger.identus.walletsdk.mercury.ApiMock
import org.hyperledger.identus.walletsdk.pollux.models.AnonCredential
import org.hyperledger.identus.walletsdk.pollux.models.JWTCredential
import org.mockito.kotlin.mock
import java.text.SimpleDateFormat
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PolluxImplTest {

    lateinit var pollux: PolluxImpl
    lateinit var castor: Castor
    lateinit var apiMock: ApiMock

    @BeforeTest
    fun setup() {
        val json = """
            {
                "name": "Schema name",
                "version": "1.1",
                "attrNames": ["name", "surname"],
                "issuerId": "did:prism:604ba1764ab89993f9a74625cc4f3e04737919639293eb382cc7adc53767f550"
            }
        """
        apiMock = ApiMock(HttpStatusCode.OK, json)
        val loggerMock = mock<PrismLogger>()
        castor = CastorImpl(apollo = ApolloImpl(), loggerMock)
        pollux = PolluxImpl(castor, apiMock)
    }

    @Test
    fun testGetSchema_whenAnoncred_thenSchemaCorrect() = runTest {
        val schema = pollux.getSchema("")
        val attrNames = listOf("name", "surname")
        assertEquals("Schema name", schema.name)
        assertEquals("1.1", schema.version)
        assertEquals(attrNames, schema.attrNames)
        assertEquals(
            "did:prism:604ba1764ab89993f9a74625cc4f3e04737919639293eb382cc7adc53767f550",
            schema.issuerId
        )
    }

    @Test
    fun testCreatePresentationDefinitionRequest_whenOptionsNoJWT_thenExceptionThrown() = runTest {
        assertFailsWith(PolluxError.InvalidJWTPresentationDefinitionError::class) {
            pollux.createPresentationDefinitionRequest(
                type = CredentialType.JWT,
                presentationClaims = PresentationClaims(
                    claims = mapOf()
                ),
                options = PresentationOptions(jwt = emptyArray(), domain = "", challenge = "")
            )
        }
    }

    @Test
    fun testCreatePresentationDefinitionRequest_whenAllCorrect_thenPresentationDefinitionRequestCorrect() =
        runTest {
            val definitionRequest = pollux.createPresentationDefinitionRequest(
                type = CredentialType.JWT,
                presentationClaims = PresentationClaims(
                    claims = mapOf(
                        "$.vc.credentialSubject.email" to InputFieldFilter(
                            type = "string",
                            value = "value"
                        )
                    )
                ),
                options = PresentationOptions(
                    name = "Testing",
                    purpose = "Test presentation definition",
                    jwt = arrayOf("EcdsaSecp256k1Signature2019"),
                    domain = "domain",
                    challenge = "challenge"
                )
            )

            assertEquals(1, definitionRequest.presentationDefinition.inputDescriptors.size)
            assertEquals(
                1,
                definitionRequest.presentationDefinition.inputDescriptors.first().constraints.fields?.size
            )
            assertEquals(
                2,
                definitionRequest.presentationDefinition.inputDescriptors.first().constraints.fields?.first()?.path?.size
            )
            assertEquals(
                "Testing",
                definitionRequest.presentationDefinition.inputDescriptors.first().name
            )
            assertEquals(
                "Test presentation definition",
                definitionRequest.presentationDefinition.inputDescriptors.first().purpose
            )
        }

    @Test
    fun testCreatePresentationSubmission_whenCredentialNotJWT_thenExceptionThrown() = runTest {
        val definitionJson = """
            {
                "presentation_definition": {
                    "id": "32f54163-7166-48f1-93d8-ff217bdb0653",
                    "input_descriptors": [
                        {
                            "id": "wa_driver_license",
                            "name": "Washington State Business License",
                            "purpose": "We can only allow licensed Washington State business representatives into the WA Business Conference",
                            "constraints": {
                                "fields": [
                                    {
                                        "path": [
                                            "$.credentialSubject.dateOfBirth",
                                            "$.credentialSubject.dob",
                                            "$.vc.credentialSubject.dateOfBirth",
                                            "$.vc.credentialSubject.dob"
                                        ]
                                    }
                                ]
                            }
                        }
                    ],
                    "format": {
                        "jwt": {
                            "alg": ["ES256K"]
                        }
                    }
                },
                "options": {
                    "domain": "domain",
                    "challenge": "challenge"
                }
            }
        """

        val presentationDefinitionRequest: PresentationDefinitionRequest =
            Json.decodeFromString(definitionJson)
        val credential = AnonCredential(
            schemaID = "",
            credentialDefinitionID = "",
            values = mapOf(),
            signatureJson = "",
            signatureCorrectnessProofJson = "",
            revocationRegistryId = null,
            revocationRegistryJson = null,
            witnessJson = "",
            json = ""
        )
        val secpKeyPair = generateSecp256k1KeyPair()

        assertFailsWith(PolluxError.CredentialTypeNotSupportedError::class) {
            pollux.createPresentationSubmission(
                presentationDefinitionRequest = presentationDefinitionRequest,
                credential = credential,
                privateKey = secpKeyPair.privateKey
            )
        }
    }

    @Test
    fun testCreatePresentationSubmission_whenPrivateKeyNotSecp256k1_thenExceptionThrown() =
        runTest {
            val definitionJson = """
                {
                    "presentation_definition": {
                        "id": "32f54163-7166-48f1-93d8-ff217bdb0653",
                        "input_descriptors": [
                            {
                                "id": "wa_driver_license",
                                "name": "Washington State Business License",
                                "purpose": "We can only allow licensed Washington State business representatives into the WA Business Conference",
                                "constraints": {
                                    "fields": [
                                        {
                                            "path": [
                                                "$.credentialSubject.dateOfBirth",
                                                "$.credentialSubject.dob",
                                                "$.vc.credentialSubject.dateOfBirth",
                                                "$.vc.credentialSubject.dob"
                                            ]
                                        }
                                    ]
                                }
                            }
                        ],
                        "format": {
                            "jwt": {
                                "alg": ["ES256K"]
                            }
                        }
                    },
                    "options": {
                        "domain": "domain",
                        "challenge": "challenge"
                    }
                }
            """

            val presentationDefinitionRequest: PresentationDefinitionRequest =
                Json.decodeFromString(definitionJson)
            val credential = JWTCredential.fromJwtString(
                "eyJhbGciOiJFUzI1NksifQ.eyJpc3MiOiJkaWQ6cHJpc206MjU3MTlhOTZiMTUxMjA3MTY5ODFhODQzMGFkMGNiOTY4ZGQ1MzQwNzM1OTNjOGNkM2YxZDI3YTY4MDRlYzUwZTpDcG9DQ3BjQ0Vsb0tCV3RsZVMweEVBSkNUd29KYzJWamNESTFObXN4RWlBRW9TQ241dHlEYTZZNnItSW1TcXBKOFkxbWo3SkMzX29VekUwTnl5RWlDQm9nc2dOYWVSZGNDUkdQbGU4MlZ2OXRKZk53bDZyZzZWY2hSM09xaGlWYlRhOFNXd29HWVhWMGFDMHhFQVJDVHdvSmMyVmpjREkxTm1zeEVpRE1rQmQ2RnRpb0prM1hPRnUtX2N5NVhtUi00dFVRMk5MR2lXOGFJU29ta1JvZzZTZGU5UHduRzBRMFNCVG1GU1REYlNLQnZJVjZDVExYcmpJSnR0ZUdJbUFTWEFvSGJXRnpkR1Z5TUJBQlFrOEtDWE5sWTNBeU5UWnJNUklnTzcxMG10MVdfaXhEeVFNM3hJczdUcGpMQ05PRFF4Z1ZoeDVzaGZLTlgxb2FJSFdQcnc3SVVLbGZpYlF0eDZKazRUU2pnY1dOT2ZjT3RVOUQ5UHVaN1Q5dCIsInN1YiI6ImRpZDpwcmlzbTpiZWVhNTIzNGFmNDY4MDQ3MTRkOGVhOGVjNzdiNjZjYzdmM2U4MTVjNjhhYmI0NzVmMjU0Y2Y5YzMwNjI2NzYzOkNzY0JDc1FCRW1RS0QyRjFkR2hsYm5ScFkyRjBhVzl1TUJBRVFrOEtDWE5sWTNBeU5UWnJNUklnZVNnLTJPTzFKZG5welVPQml0eklpY1hkZnplQWNUZldBTi1ZQ2V1Q2J5SWFJSlE0R1RJMzB0YVZpd2NoVDNlMG5MWEJTNDNCNGo5amxzbEtvMlpsZFh6akVsd0tCMjFoYzNSbGNqQVFBVUpQQ2dselpXTndNalUyYXpFU0lIa29QdGpqdFNYWjZjMURnWXJjeUluRjNYODNnSEUzMWdEZm1BbnJnbThpR2lDVU9Ca3lOOUxXbFlzSElVOTN0Snkxd1V1TndlSV9ZNWJKU3FObVpYVjg0dyIsIm5iZiI6MTY4NTYzMTk5NSwiZXhwIjoxNjg1NjM1NTk1LCJ2YyI6eyJjcmVkZW50aWFsU3ViamVjdCI6eyJhZGRpdGlvbmFsUHJvcDIiOiJUZXN0MyIsImlkIjoiZGlkOnByaXNtOmJlZWE1MjM0YWY0NjgwNDcxNGQ4ZWE4ZWM3N2I2NmNjN2YzZTgxNWM2OGFiYjQ3NWYyNTRjZjljMzA2MjY3NjM6Q3NjQkNzUUJFbVFLRDJGMWRHaGxiblJwWTJGMGFXOXVNQkFFUWs4S0NYTmxZM0F5TlRack1SSWdlU2ctMk9PMUpkbnB6VU9CaXR6SWljWGRmemVBY1RmV0FOLVlDZXVDYnlJYUlKUTRHVEkzMHRhVml3Y2hUM2UwbkxYQlM0M0I0ajlqbHNsS28yWmxkWHpqRWx3S0IyMWhjM1JsY2pBUUFVSlBDZ2x6WldOd01qVTJhekVTSUhrb1B0amp0U1haNmMxRGdZcmN5SW5GM1g4M2dIRTMxZ0RmbUFucmdtOGlHaUNVT0JreU45TFdsWXNISVU5M3RKeTF3VXVOd2VJX1k1YkpTcU5tWlhWODR3In0sInR5cGUiOlsiVmVyaWZpYWJsZUNyZWRlbnRpYWwiXSwiQGNvbnRleHQiOlsiaHR0cHM6XC9cL3d3dy53My5vcmdcLzIwMThcL2NyZWRlbnRpYWxzXC92MSJdfX0.x0SF17Y0VCDmt7HceOdTxfHlofsZmY18Rn6VQb0-r-k_Bm3hTi1-k2vkdjB25hdxyTCvxam-AkAP-Ag3Ahn5Ng"
            )
            val nonSecpKeyPair = Ed25519KeyPair.generateKeyPair()

            assertFailsWith(PolluxError.PrivateKeyTypeNotSupportedError::class) {
                pollux.createPresentationSubmission(
                    presentationDefinitionRequest = presentationDefinitionRequest,
                    credential = credential,
                    privateKey = nonSecpKeyPair.privateKey
                )
            }
        }

    @Test
    fun testCreatePresentationSubmission_whenAllCorrect_thenPresentationSubmissionProofWellFormed() =
        runTest {
            val loggerMock = mock<PrismLogger>()
            val castor: Castor = CastorImpl(apollo = ApolloImpl(), loggerMock)

            val issuerKeyPair =
                Secp256k1KeyPair.generateKeyPair(
                    Seed(MnemonicHelper.createRandomSeed()),
                    KeyCurve(Curve.SECP256K1)
                )
            val holderKeyPair =
                Secp256k1KeyPair.generateKeyPair(
                    Seed(MnemonicHelper.createRandomSeed()),
                    KeyCurve(Curve.SECP256K1)
                )
            val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
            val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

            val vtc = createVerificationTestCase(
                VerificationTestCase(
                    issuer = issuerDID,
                    holder = holderDID,
                    issuerPrv = issuerKeyPair.privateKey,
                    holderPrv = holderKeyPair.privateKey,
                    subject = """{"course": "Identus Training course Certification 2024"} """,
                    claims = PresentationClaims(
                        claims = mapOf(
                            "course" to InputFieldFilter(
                                type = "string",
                                pattern = "Identus Training course Certification 2024"
                            )
                        )
                    ),
                )
            )
            val presentationDefinitionRequest = vtc.first
            val presentationSubmissionProof = vtc.second

            assertEquals(
                presentationDefinitionRequest.presentationDefinition.id,
                presentationSubmissionProof.presentationSubmission.definitionId
            )
            assertEquals(1, presentationSubmissionProof.presentationSubmission.descriptorMap.size)
            val inputDescriptor =
                presentationDefinitionRequest.presentationDefinition.inputDescriptors.first()
            val descriptorMap =
                presentationSubmissionProof.presentationSubmission.descriptorMap.first()
            assertEquals(inputDescriptor.id, descriptorMap.id)
            assertEquals("$.verifiablePresentation[0]", descriptorMap.path)
            assertEquals(1, presentationSubmissionProof.verifiablePresentation.size)
        }

    @Test
    fun testVerifyPresentationSubmission_whenWrongJwtIssuer_thenVerifiedFalse() = runTest {
        val issuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val wrongIssuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val holderKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val issuerDID = castor.createPrismDID(wrongIssuerKeyPair.publicKey, emptyArray())
        val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

        val vtc = createVerificationTestCase(
            VerificationTestCase(
                issuer = issuerDID,
                holder = holderDID,
                issuerPrv = issuerKeyPair.privateKey,
                holderPrv = holderKeyPair.privateKey,
                subject = """{"course": "Identus Training course Certification 2024"} """,
                claims = PresentationClaims(
                    claims = mapOf(
                        "course" to InputFieldFilter(
                            type = "string",
                            pattern = "Identus Training course Certification 2024"
                        )
                    )
                ),
            )
        )
        assertFailsWith(PolluxError.VerificationUnsuccessful::class, "Issuer signature not valid") {
            pollux.verifyPresentationSubmission(
                presentationSubmission = vtc.second,
                options = PresentationSubmissionOptionsJWT(vtc.first)
            )
        }
    }

    @Test
    fun testVerifyPresentationSubmission_whenJwtSignaturesOkAndFieldsNot_thenVerifiedFalse() =
        runTest {
            val issuerKeyPair =
                Secp256k1KeyPair.generateKeyPair(
                    Seed(MnemonicHelper.createRandomSeed()),
                    KeyCurve(Curve.SECP256K1)
                )
            val holderKeyPair =
                Secp256k1KeyPair.generateKeyPair(
                    Seed(MnemonicHelper.createRandomSeed()),
                    KeyCurve(Curve.SECP256K1)
                )
            val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
            val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

            val vtc = createVerificationTestCase(
                VerificationTestCase(
                    issuer = issuerDID,
                    holder = holderDID,
                    issuerPrv = issuerKeyPair.privateKey,
                    holderPrv = holderKeyPair.privateKey,
                    subject = """{"course": "Identus Training course Certification 2023"} """,
                    claims = PresentationClaims(
                        claims = mapOf(
                            "course" to InputFieldFilter(
                                type = "string",
                                pattern = "Identus Training course Certification 2024"
                            )
                        )
                    ),
                )
            )
            assertFailsWith(
                PolluxError.VerificationUnsuccessful::class,
                "Identus Training course Certification 2023"
            ) {
                pollux.verifyPresentationSubmission(
                    presentationSubmission = vtc.second,
                    options = PresentationSubmissionOptionsJWT(vtc.first)
                )
            }
        }

    @Test
    fun testVerifyPresentationSubmission_whenJwtSignaturesAndFieldsOk_thenVerifiedOk() = runTest {
        val issuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val holderKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
        val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

        val vtc = createVerificationTestCase(
            VerificationTestCase(
                issuer = issuerDID,
                holder = holderDID,
                issuerPrv = issuerKeyPair.privateKey,
                holderPrv = holderKeyPair.privateKey,
                subject = """{"course": "Identus Training course Certification 2024"} """,
                claims = PresentationClaims(
                    claims = mapOf(
                        "course" to InputFieldFilter(
                            type = "string",
                            pattern = "Identus Training course Certification 2024"
                        )
                    )
                ),
            )
        )

        val isVerified = pollux.verifyPresentationSubmission(
            presentationSubmission = vtc.second,
            options = PresentationSubmissionOptionsJWT(vtc.first)
        )
        assertTrue(isVerified)
    }

    @Test
    fun testDescriptorPath_whenGetValue_thenArrayIndexValueAsString() = runTest {
        val issuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val holderKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
        val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

        val vtc = createVerificationTestCase(
            VerificationTestCase(
                issuer = issuerDID,
                holder = holderDID,
                issuerPrv = issuerKeyPair.privateKey,
                holderPrv = holderKeyPair.privateKey,
                subject = """{"course": "Identus Training course Certification 2024"} """,
                claims = PresentationClaims(
                    claims = mapOf(
                        "course" to InputFieldFilter(
                            type = "string",
                            pattern = "Identus Training course Certification 2024"
                        )
                    )
                ),
            )
        )
        val presentationSubmission = vtc.second

        val descriptorPath = DescriptorPath(Json.encodeToJsonElement(presentationSubmission))
        val path = "\$.verifiablePresentation[0]"
        val holderJws = descriptorPath.getValue(path)
        assertNotNull(holderJws)
        assertTrue(holderJws is String)
        val path1 = "\$.verifiablePresentation"
        val holderJws1 = descriptorPath.getValue(path1)
        assertNotNull(holderJws1)
    }

    @Test
    fun testDescriptorPath_whenClaimsAreEnum_thenValidatedOk() = runTest {
        val issuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val holderKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
        val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

        val vtc = createVerificationTestCase(
            VerificationTestCase(
                issuer = issuerDID,
                holder = holderDID,
                issuerPrv = issuerKeyPair.privateKey,
                holderPrv = holderKeyPair.privateKey,
                subject = """{"course": "Identus Training course Certification 2024"} """,
                claims = PresentationClaims(
                    claims = mapOf(
                        "course" to InputFieldFilter(
                            type = "string",
                            enum = listOf("test", "Identus Training course Certification 2024")
                        )
                    )
                ),
            )
        )

        val isVerified = pollux.verifyPresentationSubmission(
            presentationSubmission = vtc.second,
            options = PresentationSubmissionOptionsJWT(vtc.first)
        )
        assertTrue(isVerified)
    }

    @Test
    fun testDescriptorPath_whenClaimsAreConst_thenValidatedOk() = runTest {
        val issuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val holderKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
        val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

        val vtc = createVerificationTestCase(
            VerificationTestCase(
                issuer = issuerDID,
                holder = holderDID,
                issuerPrv = issuerKeyPair.privateKey,
                holderPrv = holderKeyPair.privateKey,
                subject = """{"course": "Identus Training course Certification 2024"} """,
                claims = PresentationClaims(
                    claims = mapOf(
                        "course" to InputFieldFilter(
                            type = "string",
                            const = listOf("test", "Identus Training course Certification 2024")
                        )
                    )
                ),
            )
        )

        val isVerified = pollux.verifyPresentationSubmission(
            presentationSubmission = vtc.second,
            options = PresentationSubmissionOptionsJWT(vtc.first)
        )
        assertTrue(isVerified)
    }

    @Test
    fun testDescriptorPath_whenClaimsAreValue_thenValidatedOk() = runTest {
        val issuerKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val holderKeyPair =
            Secp256k1KeyPair.generateKeyPair(
                Seed(MnemonicHelper.createRandomSeed()),
                KeyCurve(Curve.SECP256K1)
            )
        val issuerDID = castor.createPrismDID(issuerKeyPair.publicKey, emptyArray())
        val holderDID = castor.createPrismDID(holderKeyPair.publicKey, emptyArray())

        val vtc = createVerificationTestCase(
            VerificationTestCase(
                issuer = issuerDID,
                holder = holderDID,
                issuerPrv = issuerKeyPair.privateKey,
                holderPrv = holderKeyPair.privateKey,
                subject = """{"course": "Identus Training course Certification 2024"} """,
                claims = PresentationClaims(
                    claims = mapOf(
                        "course" to InputFieldFilter(
                            type = "string",
                            value = "Identus Training course Certification 2024"
                        )
                    )
                ),
            )
        )

        val isVerified = pollux.verifyPresentationSubmission(
            presentationSubmission = vtc.second,
            options = PresentationSubmissionOptionsJWT(vtc.first)
        )
        assertTrue(isVerified)
    }

    private fun generateSecp256k1KeyPair(): Secp256k1KeyPair {
        val mnemonics = listOf(
            "blade",
            "multiply",
            "coil",
            "rare",
            "fox",
            "doll",
            "tongue",
            "please",
            "icon",
            "mind",
            "gesture",
            "moral",
            "old",
            "laugh",
            "symptom",
            "assume",
            "burden",
            "appear",
            "always",
            "oil",
            "ticket",
            "vault",
            "return",
            "height"
        )
        val seed = Seed(MnemonicHelper.createSeed(mnemonics = mnemonics, passphrase = "mnemonic"))
        return Secp256k1KeyPair.generateKeyPair(seed, KeyCurve(Curve.SECP256K1))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun createVerificationTestCase(testCaseOptions: VerificationTestCase): Triple<PresentationDefinitionRequest, PresentationSubmission, String> {
        val currentDate = Calendar.getInstance()
        val nextMonthDate = currentDate.clone() as Calendar
        nextMonthDate.add(Calendar.MONTH, 1)
        val issuanceDate = currentDate.timeInMillis
        val expirationDate = nextMonthDate.timeInMillis
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val jwsHeader = JWSHeader.Builder(JWSAlgorithm.ES256K).build()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val vc = json.decodeFromString<JWTVerifiableCredential>(
            """{"@context":[
                     "https://www.w3.org/2018/credentials/v1"
                  ],
                  "type":[
                     "VerifiableCredential"
                  ],
                  "issuer":"${testCaseOptions.issuer}",
                  "issuanceDate": "${sdf.format(Date(issuanceDate))}",
                  "credentialSubject": ${testCaseOptions.subject}}"""
        )

        val ecPrivateKey = pollux.parsePrivateKey(testCaseOptions.issuerPrv)
        val claims = JWTClaimsSet.Builder()
            .issuer(testCaseOptions.issuer.toString())
            .audience(testCaseOptions.domain)
            .notBeforeTime(Date(issuanceDate))
            .expirationTime(Date(expirationDate))
            .subject(testCaseOptions.holder.toString())
            .claim("vc", vc)
            .build()
        val signedJwt = SignedJWT(jwsHeader, claims)
        val signer = ECDSASigner(
            ecPrivateKey as java.security.PrivateKey,
            com.nimbusds.jose.jwk.Curve.SECP256K1
        )
        val provider = BouncyCastleProviderSingleton.getInstance()
        signer.jcaContext.provider = provider
        signedJwt.sign(signer)
        val jwtString = signedJwt.serialize()
        val jwtCredential = JWTCredential.fromJwtString(jwtString)

        val presentationDefinition = pollux.createPresentationDefinitionRequest(
            type = CredentialType.JWT,
            presentationClaims = PresentationClaims(
                issuer = testCaseOptions.issuer.toString(),
                claims = testCaseOptions.claims.claims
            ),
            options = PresentationOptions(domain = "domain", challenge = testCaseOptions.challenge)
        )

        val presentationSubmission = pollux.createPresentationSubmission(
            presentationDefinitionRequest = presentationDefinition,
            credential = jwtCredential,
            privateKey = testCaseOptions.holderPrv
        )

        return Triple(presentationDefinition, presentationSubmission, jwtString)
    }

    data class VerificationTestCase(
        val issuer: DID,
        val holder: DID,
        val holderPrv: PrivateKey,
        val issuerPrv: PrivateKey,
        val subject: String,
        val claims: PresentationClaims,
        val domain: String = UUID.randomUUID().toString(),
        val challenge: String = UUID.randomUUID().toString()
    )
}
