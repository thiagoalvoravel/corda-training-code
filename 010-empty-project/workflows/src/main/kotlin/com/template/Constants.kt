package com.template

import net.corda.core.identity.CordaX500Name

object Constants {
    const val desiredNotaryName = "O=Notary, L=London, C=GB"
    val desiredNotary = CordaX500Name.parse(desiredNotaryName)
}