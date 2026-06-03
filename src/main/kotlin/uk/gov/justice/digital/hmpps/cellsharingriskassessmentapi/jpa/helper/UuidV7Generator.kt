package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.NoArgGenerator
import org.hibernate.annotations.IdGeneratorType
import org.hibernate.annotations.ValueGenerationType
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.generator.BeforeExecutionGenerator
import org.hibernate.generator.EventType
import org.hibernate.generator.EventTypeSets
import java.util.EnumSet
import java.util.UUID

/**
 * Generates time-ordered (v7) UUIDs for entity primary keys. Time ordering keeps inserts
 * index-friendly compared with fully random (v4) UUIDs.
 */
class UuidV7Generator : BeforeExecutionGenerator {
  companion object {
    val uuidGenerator: NoArgGenerator = Generators.timeBasedEpochGenerator(null)
  }

  override fun getEventTypes(): EnumSet<EventType> = EventTypeSets.INSERT_ONLY

  override fun generate(
    session: SharedSessionContractImplementor?,
    owner: Any?,
    currentValue: Any?,
    eventType: EventType?,
  ): UUID = uuidGenerator.generate()
}

@IdGeneratorType(UuidV7Generator::class)
@ValueGenerationType(generatedBy = UuidV7Generator::class)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class GeneratedUuidV7
