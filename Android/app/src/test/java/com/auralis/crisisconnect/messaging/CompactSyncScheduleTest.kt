package com.auralis.crisisconnect.messaging
import org.junit.Assert.*
import org.junit.Test
class CompactSyncScheduleTest {
 @Test fun foregroundResumeCannotBurst() { val s=CompactSyncSchedule();assertTrue(s.reserve(0));assertFalse(s.reserve(29_999));assertTrue(s.reserve(30_000)) }
 @Test fun paginationAndPendingAcknowledgementsContinueWithinBudget() { val s=CompactSyncSchedule();s.reserve(0);s.succeeded(500,true,60_000);assertFalse(s.reserve(30_499));assertTrue(s.reserve(30_500)) }
 @Test fun idlePollUsesJitterInsteadOfBusyLoop() { val s=CompactSyncSchedule();s.reserve(0);s.succeeded(1_000,false,60_000);assertFalse(s.reserve(180_999));assertTrue(s.reserve(181_000)) }
 @Test fun failureBackoffIsBoundedAndSuccessResetsIt() { val s=CompactSyncSchedule();var now=0L;repeat(8) { assertTrue(s.reserve(now));s.failed(now,Long.MAX_VALUE);assertFalse(s.reserve(now+29_999));now+=300_000 };s.succeeded(now,true,0);assertTrue(s.reserve(now+30_000));s.failed(now+30_000,0);assertTrue(s.reserve(now+60_000)) }
 @Test fun longSuspensionAllowsOneRequestThenEnforcesBudgetAgain() { val s=CompactSyncSchedule();s.reserve(0);s.succeeded(0,false,0);assertTrue(s.reserve(3_600_000));assertFalse(s.reserve(3_600_001)) }
}
