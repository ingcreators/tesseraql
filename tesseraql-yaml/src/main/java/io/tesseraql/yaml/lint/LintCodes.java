package io.tesseraql.yaml.lint;

/**
 * The lint codes more than one rule family raises.
 *
 * <p>A code is a constant so the uniqueness guard can see it
 * (docs/lint-restructure.md decision 4), and a code two families raise needs one declaration
 * rather than two: a second declaration is how a number quietly comes to mean two things.
 * Every other code is declared by the single family that raises it.
 */
final class LintCodes {

    static final String STEP_REFERENCE_UNRESOLVED = "TQL-BATCH-4206";

    static final String STEP_WORK_SHAPE = "TQL-FIELD-2004";

    static final String UNDEFINED_POLICY = "TQL-SEC-4030";

    static final String SFTP_HOST_KEY_UNVERIFIED = "TQL-SEC-4084";

    static final String FTPS_SERVER_UNVERIFIED = "TQL-SEC-4085";

    static final String MALFORMED_EXPRESSION = "TQL-SQL-2101";

    static final String MISSING_SQL_FILE = "TQL-SQL-2103";

    static final String MESSAGING_KEY_ON_WRONG_RECIPE = "TQL-YAML-1010";

    static final String UNDECLARED_DATASOURCE = "TQL-YAML-1035";

    static final String DATASOURCE_SPLITS_TRANSACTION = "TQL-YAML-1037";

    static final String EMIT_UNSUPPORTED = "TQL-YAML-1038";

    // A job's trigger declaration: the poll source, or two trigger kinds declared together.
    static final String INVALID_JOB_TRIGGER = "TQL-YAML-1054";

    // The shared-fragment shape: a chain, a collided expansion, or a use: where fragments do
    // not expand. Raised by StepFragments at load and by JobRules at lint.
    static final String INVALID_FRAGMENT_USE = "TQL-YAML-1062";

    static final String UNDECLARED_CONFIG_REFERENCE = "TQL-YAML-1102";

    private LintCodes() {
    }
}
