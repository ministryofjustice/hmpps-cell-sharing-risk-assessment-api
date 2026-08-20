package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.CONFLICT
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestControllerAdvice
class CellSharingRiskAssessmentApiExceptionHandler {
  @ExceptionHandler(ValidationException::class)
  fun handleValidationException(e: ValidationException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Validation exception: {}", e.message) }

  @ExceptionHandler(HttpMessageNotReadableException::class)
  fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: Couldn't read request body",
        developerMessage = e.message,
      ),
    ).also { log.info("Could not read request body: {}", e.message) }

  @ExceptionHandler(MethodArgumentNotValidException::class)
  fun handleMethodArgumentNotValidException(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Method argument not valid exception: {}", e.message) }

  @ExceptionHandler(NoResourceFoundException::class)
  fun handleNoResourceFoundException(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        userMessage = "No resource found failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("No resource found exception: {}", e.message) }

  @ExceptionHandler(AccessDeniedException::class)
  fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(FORBIDDEN)
    .body(
      ErrorResponse(
        status = FORBIDDEN,
        userMessage = "Forbidden: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.debug("Forbidden (403) returned: {}", e.message) }

  @ExceptionHandler(CsraReviewNotFoundException::class)
  fun handleCsraReviewNotFoundException(e: CsraReviewNotFoundException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        errorCode = ErrorCode.CsraReviewNotFound.name,
        userMessage = "Unexpected error: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.error("Unexpected exception", e) }

  @ExceptionHandler(MandatoryHighRiskGeneralException::class)
  fun handleMandatoryHighRiskGeneralException(e: MandatoryHighRiskGeneralException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        errorCode = ErrorCode.MandatoryHighRiskGeneral.name,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Mandatory high risk general violation: {}", e.message) }

  @ExceptionHandler(CsraAssessmentInProgressException::class)
  fun handleCsraAssessmentInProgressException(e: CsraAssessmentInProgressException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(CONFLICT)
    .body(
      ErrorResponse(
        status = CONFLICT,
        errorCode = ErrorCode.AssessmentInProgress.name,
        userMessage = "Conflict: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Assessment already in progress: {}", e.message) }

  @ExceptionHandler(StaleAnswersException::class)
  fun handleStaleAnswersException(e: StaleAnswersException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(CONFLICT)
    .body(
      ErrorResponse(
        status = CONFLICT,
        errorCode = ErrorCode.StaleAnswersVersion.name,
        userMessage = "Conflict: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Stale answers version: {}", e.message) }

  @ExceptionHandler(MethodArgumentTypeMismatchException::class)
  fun handleMethodArgumentTypeMismatchException(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        errorCode = ErrorCode.MethodArgumentTypeMismatch.name,
        userMessage = "Bad request: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Method argument type mismatch: {}", e.message) }

  // One handler for the whole family: these differ only in their error code and message, so a class each
  // would be thirty lines of copy. New answer-validation rules subclass CsraAnswerValidationException.
  @ExceptionHandler(CsraAnswerValidationException::class)
  fun handleCsraAnswerValidationException(e: CsraAnswerValidationException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        errorCode = e.errorCode.name,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Answer validation failure ({}): {}", e.errorCode.name, e.message) }

  @ExceptionHandler(Exception::class)
  fun handleException(e: Exception): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(INTERNAL_SERVER_ERROR)
    .body(
      ErrorResponse(
        status = INTERNAL_SERVER_ERROR,
        userMessage = "Unexpected error: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.error("Unexpected exception", e) }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

class CsraReviewNotFoundException(id: String) : Exception("There is no CSRA review found for ID = $id")

class MandatoryHighRiskGeneralException : Exception("The rating must be HIGH_GENERAL when there is evidence of a mandatory high-risk offence")

class CsraAssessmentInProgressException(prisonerNumber: String) : Exception("An assessment is already in progress for prisoner $prisonerNumber")

/** A submitted answer set that is internally inconsistent. Always a 400, carrying [errorCode] to discriminate. */
sealed class CsraAnswerValidationException(val errorCode: ErrorCode, message: String) : Exception(message)

class CsraRiskCategoriesInvalidException(message: String) : CsraAnswerValidationException(ErrorCode.RiskCategoriesInvalid, message)

class CsraMissingAnswerDetailException(questions: Collection<String>) :
  CsraAnswerValidationException(
    ErrorCode.MissingAnswerDetail,
    "Details are required when the answer is yes: ${questions.sorted().joinToString()}",
  )

class CsraNextReviewDateInvalidException(message: String) : CsraAnswerValidationException(ErrorCode.NextReviewDateInvalid, message)

class StaleAnswersException(requestVersion: Int, entityVersion: Int) :
  Exception(
    "Request version $requestVersion does not match current version $entityVersion — reload the answers and try again",
  )
