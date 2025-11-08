package br.com.vexpera.ktoon.decoder

/**
 * Define o tipo de estrutura raiz do documento (§5 da spec KTOON).
 */
internal enum class RootForm {
    /** Documento vazio → {} implícito */
    EMPTY_OBJECT,

    /** Documento de um único valor primitivo */
    PRIMITIVE,

    /** Documento cuja raiz é um array (ex: [#3]: ...) */
    ROOT_ARRAY,

    /** Documento cuja raiz é um objeto */
    ROOT_OBJECT
}
