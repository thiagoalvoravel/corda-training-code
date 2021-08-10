package com.template.contracts

import com.template.states.TokenState
import com.template.states.mapSumByIssuer
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

class TokenContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "com.template.contracts.TokenContract"
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Issue : Commands
        class Move : Commands
        class Redeem : Commands
    }

    // A transaction is valid if the verify() function of the contract of all the transaction's input and output states
    // does not throw an exception.
    override fun verify(tx: LedgerTransaction) {

        val command = tx.commands.requireSingleCommand<Commands>()

        val inputs = tx.inputsOfType<TokenState>()
        val outputs = tx.outputsOfType<TokenState>()
        val hasAllPositiveQuantities = inputs.all { 0 < it.quantity } && outputs.all { 0 < it.quantity }
        val allInputHolderKeys = inputs.map { it.holder.owningKey }.distinct()

        when (command.value) {
            is Commands.Issue -> requireThat {
                "No tokens should be consumed, in inputs, when issuing." using (inputs.isEmpty())
                "There should be issued tokens, in outputs." using (outputs.isNotEmpty())
                "All quantities must be above 0." using (hasAllPositiveQuantities)

                "The issuers should sign." using (command.signers.containsAll(outputs.map {it.issuer.owningKey}.distinct()))
            }

            is Commands.Move -> requireThat {
                "There should be tokens to move, in inputs." using (inputs.isNotEmpty())
                "There should be moved tokens, in outputs." using (outputs.isNotEmpty())
                "All quantities must be above 0." using hasAllPositiveQuantities

                val inputSums = inputs.mapSumByIssuer()
                val outputSums = outputs.mapSumByIssuer()
                "The list of issuers should be conserved." using (inputSums.keys == outputSums.keys)
                "The sum of quantities for each issuer should be conserved." using (inputSums.all { outputSums[it.key] == it.value })

                "The current holders should sign." using (command.signers.containsAll(allInputHolderKeys))
            }

            is Commands.Redeem -> requireThat {
                "There should be tokens to redeem, in inputs." using (inputs.isNotEmpty())
                "No tokens should be issued, in outputs, when redeeming." using (outputs.isEmpty())
                "All quantities must be above 0." using hasAllPositiveQuantities

                "The issuers should sign." using (command.signers.containsAll(outputs.map {it.issuer.owningKey}.distinct()))
                "The current holders should sign." using (command.signers.containsAll(allInputHolderKeys))
            }

            else -> throw IllegalArgumentException("Unkown command ${command.value}.")
        }
    }
}
