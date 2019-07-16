package com.github.opticyclic.corda.demo.flows.accounts

import com.github.opticyclic.corda.demo.accounts.states.IOUAccountState
import com.github.opticyclic.corda.demo.flows.AgentListener
import com.github.opticyclic.corda.demo.flows.accountService
import com.github.opticyclic.corda.demo.flows.classic.IOUResponder
import com.github.opticyclic.corda.demo.flows.identity
import com.github.opticyclic.corda.demo.flows.runAndGet
import com.github.opticyclic.corda.demo.flows.uuid
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.services.KeyManagementBackedAccountService
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Listeners
import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Listeners(AgentListener::class)
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
        val banksAccountService = banks.accountService()

        val iouValue = 1
        val flow = IOUAccountFlow(iouValue, bank1.uuid, bank2.uuid)
        val signedTx = banks.startFlow(flow).runAndGet(network)

        banks.transaction {
            val recordedTx = banks.services.validatedTransactions.getTransaction(signedTx.id)
            val ious = recordedTx!!.tx.outputs
            assertEquals(1, ious.size)

            val recordedState = ious.single().data as IOUAccountState
            assertEquals(iouValue, recordedState.value)
            assertEquals(bank1, banksAccountService.accountInfo(recordedState.lender))
            assertEquals(bank2, banksAccountService.accountInfo(recordedState.borrower))
        }
    }

    @Test
    fun `Save an IOU in accounts across nodes`() {
        val banksAccountService = banks.accountService()
        val agentsAccountService = agents.accountService()

        //Share accounts with other nodes
        banksAccountService.shareAccountInfoWithParty(bank1.uuid, agents.identity())
        agentsAccountService.shareAccountInfoWithParty(agent1.uuid, banks.identity())
        network.runNetwork()

        val iouValue = 2
        val flow = IOUAccountFlow(iouValue, bank1.uuid, agent1.uuid)
        val signedTx = banks.startFlow(flow).runAndGet(network)

        val output = signedTx.tx.outputs.single().data as IOUAccountState

        //Share the state and the account with the Bank node so that we can look up by keys
        //TODO: The test will fail if we don't do this. Ideally the framework should do this for us
        banksAccountService.shareStateAndSyncAccounts(signedTx.tx.outRefsOfType<IOUAccountState>().single(), agents.identity()).runAndGet(network)

        //Look up the IOU via the transaction and check both accounts are visible to the bank
        banks.transaction {
            val recordedTx = banks.services.validatedTransactions.getTransaction(signedTx.id)
            val ious = recordedTx!!.tx.outputs
            assertEquals(1, ious.size)

            val recordedState = ious.single().data as IOUAccountState
            assertEquals(iouValue, recordedState.value)
            assertEquals(bank1, banksAccountService.accountInfo(recordedState.lender))
            assertEquals(agent1, banksAccountService.accountInfo(recordedState.borrower))
        }
        //Check that the IOU is in the banks vault and both accounts are visible to the bank
        banks.transaction {
            val linearStateCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
            val ious = banks.services.vaultService.queryBy<IOUAccountState>(linearStateCriteria).states
            assertEquals(1, ious.size)

            val vaultState = ious.single().state.data
            assertEquals(iouValue, vaultState.value)
            assertEquals(bank1, banksAccountService.accountInfo(vaultState.lender))
            assertEquals(agent1, banksAccountService.accountInfo(vaultState.borrower))
        }
        //Check that the IOU is in the agents vault and both accounts are visible to the agent
        agents.transaction {
            val linearStateCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
            val ious = agents.services.vaultService.queryBy<IOUAccountState>(linearStateCriteria).states
            assertEquals(1, ious.size)

            val vaultState = ious.single().state.data
            assertEquals(iouValue, vaultState.value)
            assertNotNull(agentsAccountService.accountInfo(agent1.uuid))
            assertEquals(agent1.state.data.host, agents.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(vaultState.borrower)))
            assertEquals(bank1.state.data.host, agents.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(vaultState.lender)))
            assertEquals(bank1, agentsAccountService.accountInfo(vaultState.lender))
            //See https://github.com/corda/accounts/issues/31 for why this fails
            assertEquals(agent1, agentsAccountService.accountInfo(vaultState.borrower))
        }
    }
}
