package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.TokenContract
import com.template.states.TokenState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object RedeemFlows {

    @InitiatingFlow
    @StartableByRPC
    /**
     * Started by a [TokenState.holder] to redeem multiple states where it is one of the holders.
     * Because it is an [InitiatingFlow], its counterpart flow [Responder] is called automatically.
     * This constructor would be called by RPC or by [FlowLogic.subFlow]. In particular one that would be more
     * user-friendly in terms of parameters passed. For instance, given sums, it would fetch the precise states in
     * the vault. Look at [SimpleInitiator] for such an example.
     */
    class Initiator @JvmOverloads constructor(
        // By requiring an exact list of states, this flow assures absolute precision at the expense of
        // user-friendliness.
        private val inputTokens: List<StateAndRef<TokenState>>,
        override val progressTracker: ProgressTracker = tracker()) : FlowLogic<SignedTransaction>() {

        init {
            require(inputTokens.isNotEmpty()) { "inputTokens cannot be empty" }
        }
        @Suppress("ClassName")
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on parameters.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = GENERATING_TRANSACTION
            // We can only make a transaction if all states have to be marked by the same notary.
            val notary = inputTokens
                .map { it.state.notary }
                .distinct()
                .single()

            val allSigners = inputTokens
                .map { it.state.data }
                // Keep a mixed list of issuers and holders.
                .flatMap { listOf(it.issuer, it.holder) }
                // Remove duplicates as it would be an issue when initiating flows, at least.
                .toSet()
            // We don't want to sign transactions where our signature is not needed.
            if (!allSigners.contains(ourIdentity)) throw FlowException("I must be an issuer or a holder.")

            // The issuers and holders are required signers, so we express this here.
            val txCommand = Command(
                TokenContract.Commands.Redeem(),
                allSigners.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                .addCommand(txCommand)
            inputTokens.forEach { txBuilder.addInputState(it) }

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            // We are but one of the signers.
            val partlySignedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            // We need to gather the signatures of all issuers and all holders, except ourselves.
            val otherFlows = allSigners
                // Remove myself.
                .minus(ourIdentity)
                .map { initiateFlow(it) }
            val fullySignedTx =
                if (otherFlows.isEmpty()) partlySignedTx
                else subFlow(CollectSignaturesFlow(
                    partlySignedTx,
                    otherFlows,
                    GATHERING_SIGS.childProgressTracker()))

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(FinalityFlow(
                fullySignedTx,
                otherFlows,
                FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    /**
     * When you create an initiator and an associated responder, you have no assurance that a peer will launch "your"
     * responder when you launch "your" initiator. Your peer may:
     *   - use a totally different one that nonetheless follows the same back and forth choreography.
     *   - use a sub-class in order to reuse the work you did on "your" responder.
     * Here we set up our responder to be able to be sub-classed by allowing others to introduce additional checks
     * on the transaction to be signed.
     * Additionally, when "your" responder is launched, you have no assurance that the peer that triggered the flow
     * used "your" initiator. The initiating peer may well have used a sub-class of "your" initiator.
     */
    abstract class Responder(
        private val counterpartySession: FlowSession,
        override val progressTracker: ProgressTracker) : FlowLogic<SignedTransaction>() {

        constructor(counterpartySession: FlowSession) : this(counterpartySession, tracker())

        @Suppress("ClassName")
        companion object {
            object SIGNING_TRANSACTION : ProgressTracker.Step("About to sign transaction with our private key.") {
                override fun childProgressTracker() = SignTransactionFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Waiting to record transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION)
        }

        /**
         * Peers can create sub-classes and extends the checks on the transaction by overriding this dummy function.
         * In particular, peers will be well advised to add logic here to control whether they really want this
         * transaction to happen.
         */
        protected abstract fun additionalChecks(stx: SignedTransaction)

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SIGNING_TRANSACTION
            val signTransactionFlow = object : SignTransactionFlow(
                counterpartySession,
                SIGNING_TRANSACTION.childProgressTracker()) {
                override fun checkTransaction(stx: SignedTransaction) {
                    // We add our internal check for clients that want to extend this feature.
                    additionalChecks(stx)
                    // Here have the checks that presumably all peers will want to have. It is opinionated and peers
                    // are free to not use this class if they want to.
                    // Here we only automatically check that it is technically satisfactory.
                    // We don't like signing irrelevant transactions. I must be relevant.
                    val relevant = stx.toLedgerTransaction(serviceHub, false)
                        .inputsOfType<TokenState>()
                        .any { it.issuer == ourIdentity || it.holder == ourIdentity }
                    if (!relevant) throw FlowException("I must be relevant.")
                }
            }
            val txId = subFlow(signTransactionFlow).id

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(ReceiveFinalityFlow(counterpartySession, txId))
        }
    }

    /**
     * This class associates a list of [TokenState] and the sum of their [TokenState.quantity]. This helps us avoid
     * constant recalculation.
     */
    class StateAccumulator private constructor(
        private val maximumSum: Long,
        val sum: Long,
        val states: List<StateAndRef<TokenState>>) {

        constructor(maximumSum: Long, states: List<StateAndRef<TokenState>> = listOf()) : this(
            maximumSum,
            states.fold(0L) { sum, state -> Math.addExact(sum, state.state.data.quantity) },
            states)

        /**
         * Joins 2 accumulators.
         */
        fun plus(other: StateAccumulator): StateAccumulator =
            other.states.firstOrNull().let { first ->
                if (first == null) this
                else this
                    .plus(first)
                    .plus(other.minus(first))
            }

        /**
         * Add a state only if the current sum is strictly below the max sum given,
         * and update the sum as we do.
         */
        fun plus(state: StateAndRef<TokenState>) =
            if (maximumSum <= sum) this
            else StateAccumulator(
                maximumSum,
                Math.addExact(sum, state.state.data.quantity),
                states.plus(state))

        /**
         * Remove a state if it is found in the list. And update the sum as we do.
         */
        fun minus(state: StateAndRef<TokenState>) =
            if (!states.contains(state)) throw IllegalArgumentException("State not found")
            else StateAccumulator(
                maximumSum,
                Math.subtractExact(sum, state.state.data.quantity),
                states.minus(state))
    }

    @StartableByRPC
    /**
     * Allows to redeem a specific quantity of fungible tokens, as it assists in fetching them in the vault.
     */
    class SimpleInitiator(
        notary: Party,
        private val issuer: Party,
        holder: Party,
        private val totalQuantity: Long,
        override val progressTracker: ProgressTracker = tracker())
        : FlowLogic<Pair<SignedTransaction?, SignedTransaction>>() {

        /**
         * A basic search criteria for the vault.
         */
        private val tokenCriteria: QueryCriteria

        init {
            if (totalQuantity <= 0) throw IllegalArgumentException("totalQuantity must be positive")
            tokenCriteria = QueryCriteria.VaultQueryCriteria()
                .withParticipants(listOf(holder))
                .withNotary(listOf(notary))
        }

        @Suppress("ClassName")
        companion object {
            object FETCHING_TOKEN_STATES : ProgressTracker.Step("Fetching token states based on parameters.")
            object MOVING_TO_EXACT_COUNT : ProgressTracker.Step("Moving token states so as to have an exact sum.")
            object HANDING_TO_INITIATOR : ProgressTracker.Step("Handing to proper initiator.") {
                override fun childProgressTracker() = Initiator.tracker()
            }

            fun tracker() = ProgressTracker(
                FETCHING_TOKEN_STATES,
                MOVING_TO_EXACT_COUNT,
                HANDING_TO_INITIATOR)
        }

        private fun fetchWorthAtLeast(
            remainingSum: Long,
            paging: PageSpecification = PageSpecification(1))
                : StateAccumulator {
            // We reached the desired state already.
            if (remainingSum <= 0) return StateAccumulator(0L)
            val pagedStates = serviceHub.vaultService
                .queryBy(TokenState::class.java, tokenCriteria, paging)
                .states
            if (pagedStates.isEmpty()) throw FlowException("Not enough states to reach sum.")

            val fetched = pagedStates
                // The previous query cannot pre-filter by issuer so we need to drop some here.
                .filter { it.state.data.issuer == issuer }
                // We will keep only up to the point where we have have enough.
                .fold(StateAccumulator(remainingSum)) { accumulator, state ->
                    accumulator.plus(state)
                }
            // Let's fetch some more, possibly an empty list.
            return fetched.plus(fetchWorthAtLeast(
                // If this number is 0 or less, we will get an empty list.
                remainingSum - fetched.sum,
                // Take the next page
                paging.copy(pageNumber = paging.pageNumber + 1)
            ))
        }

        @Suspendable
        override fun call(): Pair<SignedTransaction?, SignedTransaction> {

            progressTracker.currentStep = FETCHING_TOKEN_STATES
            val accumulated = fetchWorthAtLeast(totalQuantity)

            progressTracker.currentStep = MOVING_TO_EXACT_COUNT
            // If we did not get an exact amount, we need to create some change for ourselves before we redeem the
            // exact quantity wanted.
            val moveTx = if (accumulated.sum <= totalQuantity) null
            else subFlow(MoveFlows.Initiator(accumulated.states, listOf(
                TokenState(issuer, ourIdentity, totalQuantity), // Index 0 in outputs.
                TokenState(issuer, ourIdentity, accumulated.sum - totalQuantity))))

            val toUse = if (moveTx == null) accumulated.states
            else listOf(moveTx.tx.outRef(0))

            progressTracker.currentStep = HANDING_TO_INITIATOR
            return Pair(moveTx, subFlow(Initiator(
                toUse,
                HANDING_TO_INITIATOR.childProgressTracker())))
        }

    }

}