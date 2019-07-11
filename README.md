# Corda Accounts Demo

Demonstrate using multiple parties/accounts in a Corda node.

This is a slightly modified version of the Corda Samples for transferring IOUs.

## Run Commands From CRaSH Shell

List all the flows registered on the node:

    flow list

Send an IOU from Agent1 to Bank1:

    flow start IOUFlow iouValue: 50, counterparty: Bank1

Get all states:

    run vaultQuery contractStateType: net.corda.core.contracts.ContractState
