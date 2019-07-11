package com.github.opticyclic.corda.demo.classic.states

import com.github.opticyclic.corda.demo.classic.contracts.IOUContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

/**
 * The state object recording an IOU agreement between two parties.
 *
 * @param value the value of the IOU.
 * @param lender the party issuing the IOU.
 * @param borrower the party receiving and approving the IOU.
 */
@BelongsToContract(IOUContract::class)
data class IOUState(val value: Int,
                    val lender: Party,
                    val borrower: Party,
                    override val linearId: UniqueIdentifier = UniqueIdentifier())
    :LinearState {
    //The public keys of the involved parties.
    override val participants: List<AbstractParty> get() = listOf(lender, borrower)

}
