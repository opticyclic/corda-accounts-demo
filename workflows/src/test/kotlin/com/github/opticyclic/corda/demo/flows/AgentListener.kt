package com.github.opticyclic.corda.demo.flows

import co.paralleluniverse.fibers.instrument.JavaAgent
import com.ea.agentloader.AgentLoader
import org.testng.ITestContext
import org.testng.ITestListener
import org.testng.ITestResult

/**
 * Automatically inject the quasar agent when running tests.
 * This removes the needs to add the command line parameter in every run configuration.
 */
class AgentListener : ITestListener {
    override fun onTestStart(result: ITestResult?) {}

    override fun onFinish(context: ITestContext?) {}

    override fun onTestSkipped(result: ITestResult?) {}

    override fun onTestSuccess(result: ITestResult?) {}

    override fun onTestFailure(result: ITestResult?) {}

    override fun onTestFailedButWithinSuccessPercentage(result: ITestResult?) {}

    override fun onStart(context: ITestContext?) {
        AgentLoader.loadAgentClass(JavaAgent::class.java.name, "")
    }
}
