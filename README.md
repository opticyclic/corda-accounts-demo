# Corda Accounts Demo

Demonstrate using multiple parties/accounts in a Corda node.

This is a slightly modified version of the Corda Samples for transferring IOUs.

There are essentially two separate projects (separated by packages) so that you can see the difference between the classic node/Party implementation and an implementation using the new accounts SDK. 

## Run Commands From CRaSH Shell

List all the flows registered on the node:

    flow list

Send an IOU from Agent1 to Bank1:

    flow start IOUFlow iouValue: 50, counterparty: Bank1

Get all states:

    run vaultQuery contractStateType: net.corda.core.contracts.ContractState

## Adding Accounts To An Existing CorDapp

Add a variable for the accounts version:

    buildscript {
        ext {
            accounts_release_version = '1.0-RC04'
        }
    }

Add the accounts artifactory repository to the list of repositories for your project:

    repositories {
        maven { url 'http://ci-artifactory.corda.r3cev.com/artifactory/corda-lib-dev' }
        maven { url 'http://ci-artifactory.corda.r3cev.com/artifactory/corda-lib' }
    }

Add the accounts dependencies to module of your CorDapp. 
For contract modules add:

    cordaCompile "com.r3.corda.lib.accounts:accounts-contracts:$accounts_release_version"

For workflow modules add:

    cordaCompile "com.r3.corda.lib.accounts:accounts-workflows:$accounts_release_version"

To use the `deployNodes` task, add the following dependencies to the root `build.gradle` file:

    cordapp "com.r3.corda.lib.accounts:accounts-contracts:$accounts_release_version"
    cordapp "com.r3.corda.lib.accounts:accounts-workflows:$accounts_release_version"

And to the `deployNodes` task itself:

    nodeDefaults {
        projectCordapp {
            deploy = false
        }
        cordapp("com.r3.corda.lib.accounts:accounts-contracts:$accounts_release_version")
        cordapp(com.r3.corda.lib.accounts:accounts-workflows:$accounts_release_version")
    }

## Modifying An Existing CorDapp to Use Accounts

States should use `PublicKey` instead of `Party` as a Party refers to a node and the PublicKey can refer to an account.

Contracts shouldn't need to change that much.

Flows need the biggest changes.

In order to create your state you need to request the PublicKey with a flow. e.g.

    val lenderKey = subFlow(RequestKeyForAccount(lenderAccountInfo.state.data)).owningKey

Once you have keys you need to have logic to determine who signs.

If your accounts are on the same node that you are running the flow on then they can all be on the `signInitialTransaction`, however, if one is on another node you need to use a `CollectSignatureFlow` 

When calling the `FinalityFlow` you will need different sessions depending on if all the accounts are on one node or on different nodes.
 
If accounts are on different nodes you need to `shareAccountInfoWithParty` before you can transact between accounts otherwise the nodes running the flows wont be aware of the accounts on the other nodes.

Currently, if accounts are on different nodes you also need to run `shareStateAndSyncAccounts` after the flow to make sure that you can use all methods to look up accountInfo.

See https://github.com/corda/accounts/issues/31 for an outstanding bug related to this.
