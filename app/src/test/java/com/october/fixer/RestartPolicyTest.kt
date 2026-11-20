package com.october.fixer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {

    @Test
    fun firstFailureRestarts() {
        assertTrue(RestartPolicy().onFailure(1_000))
    }

    @Test
    fun secondFailureWithinAMinuteDoesNot() {
        val policy = RestartPolicy()
        assertTrue(policy.onFailure(1_000))
        assertFalse(policy.onFailure(30_000))
        assertFalse(policy.onFailure(60_999)) // still inside 60 s of the restart
    }

    @Test
    fun failureAfterTheWindowRestartsAgain() {
        val policy = RestartPolicy()
        assertTrue(policy.onFailure(1_000))
        assertTrue(policy.onFailure(62_000))
        assertFalse(policy.onFailure(63_000))
    }
}
