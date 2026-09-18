package uk.gov.hmcts.cp.client;

/** The subset of a Progression case needed to decide Enforcement eligibility and build the Libra payload. */
public record ProsecutionCaseDetails(String prosecutionAuthorityOUCode, String caseUrn) {
}
