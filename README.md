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

## Adding Accounts To An Existing CorDapp

Add a variable for the accounts version:

    buildscript {
        ext {
            accounts_release_version = '1.0-RC01'
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

    cordaCompile "com.r3.corda.lib.accounts:accounts-flows:$accounts_release_version"

To use the `deployNodes` task, add the following dependencies to the root `build.gradle` file:

    cordapp "com.r3.corda.lib.accounts:accounts-contracts:$accounts_release_version"
    cordapp "com.r3.corda.lib.accounts:accounts-flows:$accounts_release_version"

And to the `deployNodes` task itself:

    nodeDefaults {
        projectCordapp {
            deploy = false
        }
        cordapp("com.r3.corda.lib.accounts:accounts-contracts:$accounts_release_version")
        cordapp(com.r3.corda.lib.accounts:accounts-workflows:$accounts_release_version")
    }
