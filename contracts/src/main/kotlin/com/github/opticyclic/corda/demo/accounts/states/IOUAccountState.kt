package com.github.opticyclic.corda.demo.accounts.states

import com.github.opticyclic.corda.demo.accounts.contracts.IOUAccountContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import java.security.PublicKey

/**
 * The state object recording an IOU agreement between two parties.
 *
 * @param value the value of the IOU.
 * @param lender the party issuing the IOU.
 * @param borrower the party receiving and approving the IOU.
 */
@BelongsToContract(IOUAccountContract::class)
data class IOUAccountState(val value: Int,
                    val lender: PublicKey,
                    val borrower: PublicKey,
                           override val linearId: UniqueIdentifier = UniqueIdentifier())
    :LinearState {
    //The public keys of the involved parties.
    override val participants: List<AbstractParty> get() = listOf(lender, borrower).map { AnonymousParty(it) }
}
