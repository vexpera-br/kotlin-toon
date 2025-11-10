package br.com.vexpera.ktoon.decoder

/**
 * Defines the root document structure type (§5 of the KTOON spec).
 */
internal enum class RootForm {
    /** Empty document → implicit {} */
    EMPTY_OBJECT,

    /** Single-value primitive document */
    PRIMITIVE,

    /** Document whose root is an array (e.g., [#3]: ...) */
    ROOT_ARRAY,

    /** Document whose root is an object */
    ROOT_OBJECT
}
