package com.github.opticyclic.corda.demo.flows.accounts

import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import com.github.opticyclic.corda.demo.flows.classic.IOUResponder
import com.github.opticyclic.corda.demo.flows.runAndGet
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.services.KeyManagementBackedAccountService
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
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
    private lateinit var banks: StartedMockNode
    private lateinit var agents: StartedMockNode
    private lateinit var bank1: StateAndRef<AccountInfo>
    private lateinit var bank2: StateAndRef<AccountInfo>
    private lateinit var agent1: StateAndRef<AccountInfo>

    @BeforeClass
    fun setup() {
        network = MockNetwork(MockNetworkParameters(
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                cordappsForAllNodes = listOf(
                        TestCordapp.findCordapp("com.github.opticyclic.corda.demo.accounts.contracts"),
                        TestCordapp.findCordapp("com.github.opticyclic.corda.demo.flows.accounts"),
                        TestCordapp.findCordapp("com.r3.corda.lib.accounts.contracts"),
                        TestCordapp.findCordapp("com.r3.corda.lib.accounts.workflows")
                )))
        //Name our test nodes for clarity when debugging
        banks = network.createPartyNode(legalName = CordaX500Name("Banks Node", "London", "GB"))
        agents = network.createPartyNode(legalName = CordaX500Name("Agents Node", "London", "GB"))

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(banks, agents).forEach { it.registerInitiatedFlow(IOUResponder::class.java) }

        //Create accounts on the nodes for testing
        bank1 = banks.services.cordaService(KeyManagementBackedAccountService::class.java).createAccount("Bank1").getOrThrow()
        bank2 = banks.services.cordaService(KeyManagementBackedAccountService::class.java).createAccount("Bank2").getOrThrow()
        agent1 = agents.services.cordaService(KeyManagementBackedAccountService::class.java).createAccount("Agent1").getOrThrow()

        network.runNetwork()
    }

    @AfterClass
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `Save an IOU using accounts on a single node`() {
        val iouValue = 1
        val flow = IOUAccountFlow(iouValue, agents.info.singleIdentity())
        val signedTx = banks.startFlow(flow).runAndGet(network)

        // We check the recorded transaction in both vaults.
        for (node in listOf(banks, agents)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.size == 1)

            val recordedState = txOutputs[0].data as IOUAccountState
            assertEquals(recordedState.value, iouValue)
            assertEquals(recordedState.lender, banks.info.singleIdentity())
            assertEquals(recordedState.borrower, agents.info.singleIdentity())
        }
    }

    @Test
    fun `Save an IOU in accounts across nodes`() {
        val iouValue = 5
        val flow = IOUAccountFlow(iouValue, agents.info.singleIdentity())
        val signedTx = banks.startFlow(flow).runAndGet(network)

        val output = signedTx.tx.outputs.single().data as IOUAccountState
        // We check the recorded IOU in both vaults.
        for (node in listOf(banks, agents)) {
            node.transaction {
                val linearStateCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
                val ious = node.services.vaultService.queryBy<IOUAccountState>(linearStateCriteria).states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, iouValue)
                assertEquals(recordedState.lender, banks.info.singleIdentity())
                assertEquals(recordedState.borrower, agents.info.singleIdentity())
            }
        }
    }
}
