package io.iohk.atala.prism.domain.models

import io.iohk.atala.prism.apollo.uuid.UUID

data class Session(
    val uuid: UUID = UUID.randomUUID4(),
    val seed: Seed
)