package com.template.states

fun Iterable<TokenState>.mapSumByIssuer() =
    groupBy({ it.issuer }) { it.quantity }
        .mapValues {
            it.value.reduce { sum, quantity -> Math.addExact(sum, quantity) }
        }
        .toMap()