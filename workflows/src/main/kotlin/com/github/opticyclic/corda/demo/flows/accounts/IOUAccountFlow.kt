package com.github.opticyclic.corda.demo.flows.accounts

import co.paralleluniverse.fibers.Suspendable
import com.github.opticyclic.corda.demo.accounts.contracts.IOUAccountContract
import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.util.*

/**
 * This flow allows two parties to come to an agreement about the IOU encapsulated
 * within an [IOUAccountState].
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding.
 * In practice we would recommend splitting up the various stages of the flow into sub-routines.
 */
@InitiatingFlow
@StartableByRPC
class IOUAccountFlow(val iouValue: Int, val lenderId: UUID, val borrowerId: UUID) : FlowLogic<SignedTransaction>() {
    /*
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
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        //Lookup the account and Public Key from the UUID
        val lenderAccountInfo = accountService.accountInfo(lenderId) ?: throw IllegalStateException("Can't find account to move from $lenderId")
        val borrowerAccountInfo = accountService.accountInfo(borrowerId) ?: throw IllegalStateException("Can't find account to move to $borrowerId")

        val lenderKey = subFlow(RequestKeyForAccount(lenderAccountInfo.state.data)).owningKey
        val borrowerKey = subFlow(RequestKeyForAccount(borrowerAccountInfo.state.data)).owningKey

        //Create the output.
        val iouState = IOUAccountState(iouValue, lenderKey, borrowerKey)
        val command = Command(IOUAccountContract.Commands.Create(), iouState.participants.map { it.owningKey })

        //Build the transaction.
        val txBuilder = TransactionBuilder(notary)
                .addOutputState(iouState, IOUAccountContract.IOU_CONTRACT_ID)
                .addCommand(command)

        // Verify that the transaction is valid.
        txBuilder.verify(serviceHub)

        //Sign the transaction with our identity.
        progressTracker.currentStep = SIGNING
        //Sign with our node key AND the private key from the lender account
        //since due to design we must be hosts of that account
        var keysToSignWith = mutableListOf(ourIdentity.owningKey, lenderKey)
        //Only add the borrower account if it is hosted on this node (potentially it might be on a different node)
        if (borrowerAccountInfo.state.data.host == serviceHub.myInfo.legalIdentitiesAndCerts.first().party) {
            keysToSignWith.add(borrowerKey)
        }
        val locallySignedTx = serviceHub.signInitialTransaction(txBuilder, keysToSignWith)

        //We have to do 2 different flows depending on whether the other account is on our node or a different node
        if (borrowerAccountInfo.state.data.host == serviceHub.myInfo.legalIdentitiesAndCerts.first().party) {
            //Notarise and record the transaction in just our vault.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(locallySignedTx, emptyList()))
        } else {
            //Send the state to the counterparty and get it back with their signature.
            progressTracker.currentStep = COLLECTING
            val borrowerSession = initiateFlow(borrowerAccountInfo.state.data.host)
            val borrowerSignature = subFlow(CollectSignatureFlow(locallySignedTx, borrowerSession, borrowerKey))
            val fullySignedTx = locallySignedTx.withAdditionalSignatures(borrowerSignature)
            //Notarise and record the transaction in both parties' vaults.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(fullySignedTx, listOf(borrowerSession)))
        }
    }
}

@InitiatedBy(IOUAccountFlow::class)
class IOUAccountResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is IOUAccountState)
                val iou = output as IOUAccountState
                "IOUs with a value over 100 are not accepted." using (iou.value <= 100)
            }
        }
        val txId = subFlow(signTransactionFlow).id

        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
