package com.github.opticyclic.corda.demo.flows.classic

import co.paralleluniverse.fibers.Suspendable
import com.github.opticyclic.corda.demo.classic.contracts.IOUContract
import com.github.opticyclic.corda.demo.classic.states.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

/**
 * This flow allows two parties to come to an agreement about the IOU encapsulated
 * within an [IOUState].
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding.
 * In practice we would recommend splitting up the various stages of the flow into sub-routines.
 */
@InitiatingFlow
@StartableByRPC
class IOUFlow(val iouValue: Int, val counterparty: Party) : FlowLogic<SignedTransaction>() {
    /**
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each checkpoint is reached.
     * See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object BUILDING : Step("Building a new transaction.")
        object SIGNING : Step("Signing the transaction with our private key.")
        object COLLECTING : Step("Collecting the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                BUILDING,
                SIGNING,
                COLLECTING,
                FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = BUILDING

        //Create the output.
        val iouState = IOUState(iouValue, serviceHub.myInfo.legalIdentities.first(), counterparty)
        val command = Command(IOUContract.Commands.Create(), iouState.participants.map { it.owningKey })

        //Build the transaction.
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary)
                .addOutputState(iouState, IOUContract.IOU_CONTRACT_ID)
                .addCommand(command)

        // Verify that the transaction is valid.
        txBuilder.verify(serviceHub)

        //Sign the transaction with our identity.
        progressTracker.currentStep = SIGNING
        val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

        //Send the state to the counterparty and get it back with their signature.
        progressTracker.currentStep = COLLECTING
        val counterpartySession = initiateFlow(counterparty)
        val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, listOf(counterpartySession), Companion.COLLECTING.childProgressTracker()))

        //Notarise and record the transaction in both parties' vaults.
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(fullySignedTx, listOf(counterpartySession), Companion.FINALISING.childProgressTracker()))
    }
}

@InitiatedBy(IOUFlow::class)
class IOUResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is IOUState)
                val iou = output as IOUState
                "IOUs with a value over 100 are not accepted." using (iou.value <= 100)
            }
        }
        val txId = subFlow(signTransactionFlow).id

        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
