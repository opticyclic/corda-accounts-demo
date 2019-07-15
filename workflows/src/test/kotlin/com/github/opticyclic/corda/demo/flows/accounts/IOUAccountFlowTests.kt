package com.github.opticyclic.corda.demo.flows.accounts

import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import kotlin.test.assertEquals

class IOUAccountFlowTests {
    private lateinit var network: MockNetwork
    private lateinit var megaCorp: StartedMockNode
    private lateinit var miniCorp: StartedMockNode

    @BeforeClass
    fun setup() {
        network = MockNetwork(MockNetworkParameters(
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                cordappsForAllNodes = listOf(
                        TestCordapp.findCordapp("com.github.opticyclic.corda.demo.accounts.contracts"),
                        TestCordapp.findCordapp("com.github.opticyclic.corda.demo.flows.accounts")
                )))
        megaCorp = network.createPartyNode()
        miniCorp = network.createPartyNode()
        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(megaCorp, miniCorp).forEach { it.registerInitiatedFlow(IOUAccountResponder::class.java) }
        network.runNetwork()
    }

    @AfterClass
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `recorded transaction has no inputs and a single output, the input IOU`() {
        val iouValue = 1
        val flow = IOUAccountFlow(iouValue, miniCorp.info.singleIdentity())
        val future = megaCorp.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both vaults.
        for (node in listOf(megaCorp, miniCorp)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.size == 1)

            val recordedState = txOutputs[0].data as IOUAccountState
            assertEquals(recordedState.value, iouValue)
            assertEquals(recordedState.lender, megaCorp.info.singleIdentity())
            assertEquals(recordedState.borrower, miniCorp.info.singleIdentity())
        }
    }

    @Test
    fun `flow records the correct IOU in both parties' vaults`() {
        val iouValue = 5
        val flow = IOUAccountFlow(iouValue, miniCorp.info.singleIdentity())
        val future = megaCorp.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        val output = signedTx.tx.outputs.single().data as IOUAccountState
        // We check the recorded IOU in both vaults.
        for (node in listOf(megaCorp, miniCorp)) {
            node.transaction {
                val linearStateCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
                val ious = node.services.vaultService.queryBy<IOUAccountState>(linearStateCriteria).states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, iouValue)
                assertEquals(recordedState.lender, megaCorp.info.singleIdentity())
                assertEquals(recordedState.borrower, miniCorp.info.singleIdentity())
            }
        }
    }
}
