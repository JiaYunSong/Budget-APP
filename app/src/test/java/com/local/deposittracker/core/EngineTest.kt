package com.local.deposittracker.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class EngineTest {
    private val date = LocalDate.parse("2026-10-05")
    private fun state(balance: Long = 10_000_000) = Ledger(accounts = listOf(Account(id = "a", name = "工商银行", balance = balance), Account(id = "b", name = "招商银行", balance = 0)))
    private fun deposit(principal: Long = 5_000_000) = Deposit(id = "d", name = "三个月定期", principal = principal, annualRateText = "1.3500", startDate = "2026-10-05", endDate = "2027-01-05", sourceAccountId = "a", targetAccountId = "a")
    private fun yearly() = deposit().copy(startDate = "2025-01-01", endDate = "2026-01-01", annualRateText = "2")
    private fun rule(day: Int = 15) = MonthlyRule(id = "r", name = "工资结余", amount = 300_000, dayOfMonth = day, startDate = "2026-07-01", targetAccountId = "a")
    private fun throws(block: () -> Unit) { try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} }

    @Test fun creationPreservesAssets() {
        val s = Engine.createDeposit(state(), deposit(), currentDate = date)
        assertEquals(5_000_000L, s.accounts[0].balance); assertEquals(5_000_000L, s.principal()); assertEquals(10_000_000L, s.total(date))
        assertEquals("FIXED_DEPOSIT_CREATE", s.transactions.single().type)
    }
    @Test fun yearlyInterestIsExact() { assertEquals(100_000L, yearly().interest()); assertEquals(5_100_000L, yearly().maturity()) }
    @Test fun actualDaysExample() { assertEquals(17_014L, deposit().interest()) }
    @Test fun maturityAddsOnlyInterest() {
        val s = Engine.createDeposit(state(), yearly(), currentDate = LocalDate.parse("2025-01-01"))
        val after = Engine.settle(s, LocalDate.parse("2026-01-01"))
        assertEquals(10_100_000L, after.cash()); assertEquals(0L, after.principal()); assertEquals(10_100_000L, after.total())
        assertEquals("MATURED", after.deposits.single().status); assertEquals("2026-01-01", after.deposits.single().settledAt)
    }
    @Test fun monthlyCatchesUpThreeMonths() {
        val july = Engine.settle(state(0).copy(monthlyRules = listOf(rule())), LocalDate.parse("2026-07-15"))
        val after = Engine.settle(july, LocalDate.parse("2026-10-20"))
        assertEquals(1_200_000L, after.cash()); assertEquals(4, after.transactions.size)
        assertEquals(listOf("2026-07-15", "2026-08-15", "2026-09-15", "2026-10-15"), after.transactions.map { it.date })
    }
    @Test fun openingTenTimesNeverDuplicates() {
        var s = Engine.createDeposit(state().copy(monthlyRules = listOf(rule())), deposit(), currentDate = date)
        val through = LocalDate.parse("2027-02-01")
        s = Engine.settle(s, through); val original = s
        repeat(10) { s = Engine.settle(s, through) }
        assertEquals(original, s); assertEquals(1, s.transactions.count { it.type == "FIXED_DEPOSIT_MATURE" })
    }
    @Test fun thirtyFirstClampsToFebruaryAndLeapDay() {
        for ((year, expected) in listOf(2026 to "2026-02-28", 2028 to "2028-02-29")) {
            val r = rule(31).copy(startDate = "$year-02-01")
            val s = Engine.settle(state(0).copy(monthlyRules = listOf(r)), LocalDate.parse("$year-03-01"))
            assertEquals(expected, s.transactions.single().date); assertEquals(300_000L, s.cash())
        }
    }
    @Test fun clockRollbackPreservesSettlements() {
        val s = Engine.settle(state().copy(monthlyRules = listOf(rule())), LocalDate.parse("2026-10-20"))
        assertEquals(s, Engine.settle(s, LocalDate.parse("2026-08-01")))
    }
    @Test fun ruleEndDateIsInclusive() {
        val r = rule().copy(endDate = "2026-08-15")
        val s = Engine.settle(state(0).copy(monthlyRules = listOf(r)), LocalDate.parse("2027-01-01"))
        assertEquals(600_000L, s.cash()); assertEquals(2, s.transactions.size)
    }
    @Test fun ruleStartsAfterDueDate() {
        val r = rule().copy(startDate = "2026-07-20")
        assertTrue(Engine.settle(state().copy(monthlyRules = listOf(r)), LocalDate.parse("2026-07-31")).transactions.isEmpty())
    }
    @Test fun negativeBalanceIsAllowedAndAssetsStayConstant() {
        assertEquals(3_000_000L, Engine.createDeposit(state(3_000_000), deposit(), currentDate = date).total(date))
        assertEquals(-2_000_000L, Engine.createDeposit(state(3_000_000), deposit(), true, date).cash())
    }
    @Test fun earlyWithdrawalUsesActualAmount() {
        val s = Engine.createDeposit(state(), deposit(), currentDate = date)
        val after = Engine.earlyWithdraw(s, "d", 5_003_500, "2026-11-01", LocalDate.parse("2026-11-01"))
        assertEquals(10_003_500L, after.total()); assertEquals("EARLY", after.deposits.single().status)
        assertEquals(after, Engine.settle(after, LocalDate.parse("2027-02-01")))
    }
    @Test fun monthlyAndManualInterestModes() {
        assertEquals(16_875L, deposit().copy(interestMode = "MONTH", months = 3).interest())
        assertEquals(20_000L, deposit().copy(interestMode = "MANUAL", manualMaturityAmount = 5_020_000).interest())
    }
    @Test fun rolloverPreservesLifecycleAndAssets() {
        val settled = Engine.createDeposit(state(), yearly(), currentDate = LocalDate.parse("2026-01-01"))
        val next = deposit().copy(id = "next", startDate = "2026-01-01", endDate = "2027-01-01", principal = 5_100_000, parentDepositId = "d")
        val result = Engine.createDeposit(settled, next, currentDate = LocalDate.parse("2026-01-01"))
        assertEquals(settled.total(), result.total()); assertEquals("ROLLED", result.deposits.first().status)
        assertEquals("d", result.deposits.last().parentDepositId)
        throws { Engine.createDeposit(result, next.copy(id = "third"), currentDate = LocalDate.parse("2026-01-01")) }
    }
    @Test fun transfersDoNotAffectNetAssets() {
        val s = Engine.transfer(state(), "a", "b", 300_000, "2026-01-01", "测试")
        assertEquals(10_000_000L, s.total()); assertEquals(0L, s.transactions.sumOf { it.amount })
    }
    @Test fun missingTargetFallsBackToSource() {
        val s = state(0).copy(deposits = listOf(yearly().copy(targetAccountId = "deleted")))
        assertEquals(5_100_000L, Engine.settle(s, date).accounts.first().balance)
    }
    @Test fun missingBothAccountsCreatesRecoveryAccount() {
        val s = Ledger(deposits = listOf(yearly()))
        val after = Engine.settle(s, date)
        assertEquals("待处理余额", after.accounts.single().name); assertEquals(5_100_000L, after.accounts.single().balance)
    }
    @Test fun deletingRuleReversesGeneratedContributions() {
        val s = Engine.settle(state(0).copy(monthlyRules = listOf(rule())), date)
        val after = Engine.deleteRule(s, "r"); assertTrue(after.transactions.isEmpty()); assertEquals(0L, after.cash()); assertTrue(after.monthlyRules.isEmpty())
    }
    @Test fun excludedAccountAlsoExcludesItsDeposits() {
        val s = Engine.createDeposit(state().copy(accounts = state().accounts.map { it.copy(includeInTotal = false) }), deposit(), currentDate = date)
        assertEquals(0L, s.total())
    }
    @Test fun futureDepositReservesWithoutDeductingAndExecutesOnlyOnce() {
        val start = date.plusDays(10)
        val s = Engine.createDeposit(state(), deposit().copy(startDate = start.toString()), currentDate = date)
        assertEquals("PLANNED", s.deposits.single().status)
        assertEquals(10_000_000L, s.cash()); assertEquals(0L, s.principal()); assertEquals(10_000_000L, s.total(date))
        assertEquals(5_000_000L, s.futureDeposits()); assertEquals(5_000_000L, s.availableCash())
        assertTrue(s.events(YearMonth.from(start), start).isEmpty()); assertTrue(s.dailyInterest(date).isEmpty())
        assertEquals(s, Engine.settle(s, start.minusDays(1)))
        val actual = Engine.settle(s, start)
        assertEquals(5_000_000L, actual.cash()); assertEquals(5_000_000L, actual.principal()); assertEquals(0L, actual.reserved())
        assertEquals(10_000_000L, actual.total(start)); assertEquals("ACTIVE", actual.deposits.single().status)
        assertEquals(1, actual.events(YearMonth.from(start), start).size)
        assertEquals(actual, Engine.settle(actual, start))
    }
    @Test fun cancellingFutureDepositDoesNotChangeBalance() {
        val s = Engine.createDeposit(state(), deposit().copy(startDate = "2026-11-01"), currentDate = date)
        val cancelled = Engine.deleteTransaction(s, s.transactions.single().id)
        assertEquals(state().cash(), cancelled.cash()); assertEquals(0L, cancelled.reserved()); assertTrue(cancelled.deposits.isEmpty())
        assertEquals(cancelled, Engine.settle(cancelled, LocalDate.parse("2027-02-01")))
    }
    @Test fun futureInvestmentTransferReservesOnlyOriginalBank() {
        val start = date.plusDays(10)
        val s = Engine.invest(state(), deposit().copy(startDate = start.toString(), sourceAccountId = "b", targetAccountId = "b"), "a", date)
        assertEquals(10_000_000L, s.cash()); assertEquals(5_000_000L, s.reserved("a")); assertEquals(0L, s.reserved("b"))
        assertEquals(5_000_000L, s.reserved()); assertEquals(5_000_000L, s.futureDeposits()); assertEquals(0L, s.scoped(setOf("b")).reserved())
        val actual = Engine.settle(s, start)
        assertEquals(5_000_000L, actual.accounts[0].balance); assertEquals(0L, actual.accounts[1].balance)
        assertEquals(10_000_000L, actual.total(start)); assertTrue(actual.transactions.all { it.applied })
        assertEquals(10_000_000L, Engine.deleteTransaction(actual, actual.transactions.first().id).cash())
        assertEquals(10_000_000L, Engine.deleteTransaction(s, s.transactions.first().id).cash())
    }
    @Test fun futureTransferIsReservedAndCannotFundOtherPlansEarly() {
        val start = date.plusDays(10)
        val s = Engine.transfer(state(), "a", "b", 2_000_000, start.toString(), "未来转出", date)
        assertEquals(10_000_000L, s.cash()); assertEquals(8_000_000L, s.availableCash()); assertEquals(0L, s.futureDeposits())
        assertEquals(0L, s.reserved("b")); assertEquals(10_000_000L, Engine.deleteTransaction(s, s.transactions.first().id).cash())
        val after = Engine.settle(s, start)
        assertEquals(8_000_000L, after.accounts[0].balance); assertEquals(2_000_000L, after.accounts[1].balance)
        assertEquals(after, Engine.settle(after, start))
    }
    @Test fun missedFutureStartAndMaturityCatchUpTogether() {
        val s = Engine.createDeposit(state(), deposit().copy(startDate = "2026-11-01"), currentDate = date)
        val end = LocalDate.parse("2027-01-05"); val after = Engine.settle(s, end.plusDays(30))
        assertEquals("MATURED", after.deposits.single().status); assertEquals(0L, after.reserved())
        assertEquals(add(10_000_000L, s.deposits.single().interest()), after.total(end))
        assertEquals(after, Engine.settle(after, end.plusDays(30)))
    }
    @Test fun futurePlansRoundTripInBackupsAndOldCsv() {
        val s = Engine.createDeposit(state(), deposit().copy(startDate = "2026-11-01"), currentDate = date)
        assertEquals(s, BackupCodec.parse(BackupCodec.export(s)).ledger())
        assertEquals(s, CsvCodec.preview(CsvCodec.export(s, complete = true), state(), date).complete!!.ledger())
        val preview = CsvCodec.preview(CsvCodec.export(s), state(), date)
        assertTrue(preview.errors.isEmpty()); val imported = CsvCodec.import(state(), preview, true, true, date)
        assertEquals(10_000_000L, imported.cash()); assertEquals(5_000_000L, imported.reserved())
    }
    @Test fun overReservedCashCanBeNegativeAndAccountMergeKeepsPlans() {
        val s = Engine.createDeposit(state(100), deposit().copy(startDate = "2026-11-01"), currentDate = date)
        assertEquals(-4_999_900L, s.availableCash())
        val merged = Engine.deleteAccounts(s, setOf("a"), "b")
        assertEquals(100L, merged.cash()); assertEquals(5_000_000L, merged.reserved("b"))
        assertEquals(100L, Engine.settle(merged, LocalDate.parse("2026-11-01")).total())
    }
    @Test fun earlyWithdrawalCannotCreditFutureMoney() {
        val s = Engine.createDeposit(state(), deposit(), currentDate = date)
        throws { Engine.earlyWithdraw(s, "d", 5_003_500, "2026-11-01", date) }
        assertEquals(10_000_000L, s.total())
    }
    @Test fun editingPreservesPrincipalAndSettledReturnAccount() {
        val s = Engine.createDeposit(state(), deposit(), currentDate = date)
        val edited = Engine.editDeposit(s, "d", "新名称", "备注", "b")
        assertEquals(s.total(), edited.total()); assertEquals(s.deposits.single().principal, edited.deposits.single().principal)
        assertEquals(s.deposits.single().startDate, edited.deposits.single().startDate)
        val settled = Engine.settle(edited, LocalDate.parse("2027-01-05"))
        throws { Engine.editDeposit(settled, "d", "名称", "", "a") }
        val renamed = Engine.editDeposit(settled, "d", "历史名称", "保留资金历史", "b")
        assertEquals(settled.accounts, renamed.accounts); assertEquals(settled.transactions, renamed.transactions)
    }
    @Test fun incomeCanPartiallyRepairExplicitNegativeBalance() {
        val s = Engine.change(state(-200_000), "a", 100_000, "MANUAL_INCOME", "2026-01-01", "")
        assertEquals(-100_000L, s.cash())
    }
    @Test fun accruedIsCappedAndOptIn() {
        val s = state(0).copy(deposits = listOf(deposit()), settings = Settings(includeAccruedInterest = true))
        assertEquals(5_000_000L, s.total(date)); assertEquals(5_017_014L, s.total(LocalDate.parse("2027-03-01")))
    }
    @Test fun backupRestoresEveryFieldAndRollover() {
        var s = Engine.createDeposit(state().copy(monthlyRules = listOf(rule())), yearly(), currentDate = date)
        s = Engine.createDeposit(s, deposit().copy(id = "next", parentDepositId = "d"), currentDate = date)
        s = s.copy(settings = Settings(theme = "DARK", hideMoney = true, lastBackup = "2026-10-05"))
        val restored = BackupCodec.parse(BackupCodec.export(s)).ledger()
        assertEquals(s, restored); assertEquals(s.total(), restored.total()); assertEquals(s.transactions, restored.transactions)
    }
    @Test fun malformedBackupFailsWithoutChangingSource() {
        val s = state(); throws { BackupCodec.parse(BackupCodec.export(s).replace("\"version\": 1", "\"version\": 999")) }
        throws { BackupCodec.validate(s.copy(accounts = s.accounts + s.accounts.first())) }
        assertEquals(10_000_000L, s.total())
    }
    @Test fun csvHasBomAndSupportsQuotesCommasAndNewlines() {
        val d = deposit().copy(name = "银行,\"定期\"", note = "第一行\n第二行")
        val s = state().copy(deposits = listOf(d)); val csv = CsvCodec.export(s)
        assertTrue(csv.startsWith("\uFEFF")); val preview = CsvCodec.preview(csv, state(), date)
        assertTrue(preview.errors.isEmpty()); assertEquals(d.name, preview.deposits.single().name); assertEquals(d.note, preview.deposits.single().note)
    }
    @Test fun csvDuplicatesCanBeSkippedOrIncluded() {
        val s = Engine.createDeposit(state(), deposit(), currentDate = date)
        val preview = CsvCodec.preview(CsvCodec.export(s), s, date)
        assertEquals(1, preview.duplicates.size)
        assertEquals(s, CsvCodec.import(s, preview, true, false, date))
        assertEquals(2, CsvCodec.import(s, preview, false, true, date).deposits.size)
    }
    @Test fun csvErrorPointsToRowAndInvalidDate() {
        val text = "名称,本金,年利率,开始日期,结束日期,来源账户\n测试,50000,1.35,2026-10-05,wrong,工商银行"
        val p = CsvCodec.preview(text, state(), date)
        assertTrue(p.errors.single().contains("第2行")); assertTrue(p.errors.single().contains("结束日期格式错误"))
    }
    @Test fun csvImportDoesNotSilentlyRecreditHistoricalAssets() {
        val s = Engine.createDeposit(state(), yearly(), currentDate = date)
        assertTrue(CsvCodec.preview(CsvCodec.export(s), s, date).errors.single().contains("历史已结算"))
    }
    @Test fun csvFormulaInjectionIsEscapedAndRestoresOriginal() {
        val d = deposit().copy(name = "=SUM(1,2)", note = "@malicious")
        val csv = CsvCodec.export(state().copy(deposits = listOf(d)))
        assertTrue(csv.contains("'=SUM")); assertEquals(d.name, CsvCodec.preview(csv, state(), date).deposits.single().name)
    }
    @Test fun calendarDoesNotDoubleCountExecutedRules() {
        val s = Engine.settle(state(0).copy(monthlyRules = listOf(rule())), LocalDate.parse("2026-10-20"))
        val events = s.events(YearMonth.of(2026, 10), LocalDate.parse("2026-10-20")); assertEquals(1, events.size); assertFalse(events.single().forecast)
        assertTrue(s.events(YearMonth.of(2026, 11), LocalDate.parse("2026-10-20")).isEmpty())
    }
    @Test fun amountsRejectFloatingPrecisionAndOverflow() {
        assertEquals(123456L, money("1234.56")); throws { money("1.001") }; throws { money("10000000000000") }
    }

    @Test fun deletedMonthlyOccurrenceNeverReturns() {
        val s = Engine.settle(state(0).copy(monthlyRules = listOf(rule())), date)
        val after = Engine.deleteTransaction(s, s.transactions.first().id)
        assertEquals(600_000L, after.cash())
        assertEquals(after, Engine.settle(after, date))
        val later = Engine.settle(after, LocalDate.parse("2026-11-20"))
        assertEquals(1_200_000L, later.cash())
        assertFalse(later.transactions.any { it.id == s.transactions.first().id })
    }
    @Test fun deletingSpentRuleReportsAccurateNegativeBalance() {
        val s = Engine.settle(state(0).copy(monthlyRules = listOf(rule())), date)
        val spent = Engine.change(s, "a", -900_000, "MANUAL_EXPENSE", "2026-10-01", "")
        val after = Engine.deleteRule(spent, "r")
        assertEquals(-900_000L, after.cash()); assertEquals(1, after.transactions.size)
    }
    @Test fun transferDeletionReversesBothAccounts() {
        val s = Engine.transfer(state(), "a", "b", 300_000, "2026-01-01", "")
        val after = Engine.deleteTransaction(s, s.transactions.first().id)
        assertEquals(state().accounts.map { it.balance }, after.accounts.map { it.balance })
        assertTrue(after.transactions.isEmpty())
    }
    @Test fun deletingInvestmentReversesMaturityAndDescendants() {
        val settled = Engine.createDeposit(state(), yearly(), currentDate = date)
        val next = deposit().copy(id = "next", parentDepositId = "d", principal = 5_100_000)
        val rolled = Engine.createDeposit(settled, next, currentDate = date)
        val after = Engine.deleteTransaction(rolled, rolled.transactions.first().id)
        assertTrue(after.deposits.isEmpty()); assertTrue(after.transactions.isEmpty())
        assertEquals(10_000_000L, after.cash())
    }
    @Test fun fixedIncomeRulesAndAccountScopesRemainDistinct() {
        val s = Engine.settle(state(0).copy(monthlyRules = listOf(rule().copy(kind = "FIXED_INCOME", targetAccountId = "b"))), date)
        assertTrue(s.transactions.all { it.type == "FIXED_INCOME" })
        assertEquals(0L, s.scoped(setOf("a")).cash()); assertEquals(900_000L, s.scoped(setOf("b")).cash())
        assertEquals(s.cash(), s.scoped(setOf("a", "b")).cash())
        assertEquals(s, BackupCodec.parse(BackupCodec.export(s)).ledger())
    }
    @Test fun futureRuleHasNoRecordsAndNoEndLimit() {
        val s = state(0).copy(monthlyRules = listOf(rule().copy(startDate = "2027-01-01")))
        assertEquals(s, Engine.settle(s, date))
        assertTrue(s.events(YearMonth.of(2027, 1), date).isEmpty())
        assertEquals(300_000L, Engine.settle(s, LocalDate.parse("2027-01-15")).cash())
    }

    @Test fun investmentTransferIsAtomicAndReversibleWithNegativeCash() {
        val original = state(100)
        val d = deposit(1000).copy(sourceAccountId = "b", targetAccountId = "b")
        val after = Engine.invest(original, d, "a", date)
        assertEquals(-900L, after.accounts.first().balance); assertEquals(0L, after.accounts.last().balance)
        assertEquals(100L, after.total(date)); assertEquals(3, after.transactions.size)
        val restored = Engine.deleteTransaction(after, after.transactions.first().id)
        assertEquals(100L, restored.cash()); assertTrue(restored.deposits.isEmpty()); assertTrue(restored.transactions.isEmpty())
    }
    @Test fun expenseAndTransferAllowNegativeBalances() {
        val after = Engine.change(state(0), "a", -100, "MANUAL_EXPENSE", "2026-01-01", "")
        assertEquals(-100L, after.cash())
        val moved = Engine.transfer(after, "a", "b", 200, "2026-01-01", "")
        assertEquals(-100L, moved.total()); assertEquals(-300L, moved.accounts.first().balance)
    }
    @Test fun dailyIncomeUsesActualDaysAndStopsAtMaturity() {
        val s = state().copy(deposits = listOf(yearly()))
        assertEquals(274L, s.dailyInterest(LocalDate.parse("2025-06-01")).single().second)
        assertTrue(s.dailyInterest(LocalDate.parse("2026-01-01")).isEmpty())
        val monthly = state().copy(deposits = listOf(deposit().copy(interestMode="MONTH")))
        assertEquals(183L, monthly.dailyInterest(date).single().second)
    }
    @Test fun accountDeletionMovesNegativeBalancesAndReferences() {
        val s = Engine.invest(state(100), deposit(1000), currentDate = date).copy(monthlyRules=listOf(rule()))
        val after = Engine.deleteAccounts(s, setOf("a"), "b")
        assertEquals(s.total(date), after.total(date)); assertEquals(1, after.accounts.size)
        assertEquals("b", after.deposits.single().sourceAccountId); assertEquals("b", after.monthlyRules.single().targetAccountId)
        assertTrue(after.transactions.all { it.accountId == "b" }); BackupCodec.validate(after)
        throws { Engine.deleteAccounts(s, setOf("a"), null) }
        assertTrue(Engine.deleteAccounts(s, setOf("a", "b"), null).transactions.isEmpty())
    }
    private fun pictureJson(): String = Pictures.encode(listOf(Picture("截图.png", "image/png", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=")))
    @Test fun jsonAndCompleteCsvRestoreAllSixKindsWithImages() {
        var s = Engine.change(state(), "a", 123, "MANUAL_INCOME", "2026-01-01", "截图")
        s = Engine.change(s, "a", -100, "MANUAL_EXPENSE", "2026-01-01", "")
        s = Engine.transfer(s, "a", "b", 100, "2026-01-01", "")
        s = Engine.invest(s, deposit(), currentDate=date)
        s = Engine.settle(s.copy(monthlyRules=listOf(rule(),rule().copy(id="fixed",kind="FIXED_INCOME"))),date)
        s = s.copy(transactions = s.transactions.map { it.copy(imagesJson=pictureJson()) }, deposits=s.deposits.map { it.copy(imagesJson=pictureJson()) },monthlyRules=s.monthlyRules.map { it.copy(imagesJson=pictureJson()) })
        assertEquals(s, BackupCodec.parse(BackupCodec.export(s)).ledger())
        val preview = CsvCodec.preview(CsvCodec.export(s, complete=true), Ledger(), date)
        assertNotNull(preview.complete); assertEquals(s, CsvCodec.import(Ledger(), preview, true, true, date))
        Pictures.validate(pictureJson())
        throws { Pictures.validate(Pictures.encode(listOf(Picture("bad.jpg",base64="eHh4eHh4eHh4eHh4eHh4eA==")))) }
    }
    @Test fun csvAssetImageImportPreservesPortableBytes() {
        val s = state().copy(deposits=listOf(deposit().copy(imagesJson=pictureJson())))
        val preview = CsvCodec.preview(CsvCodec.export(s), state(), date)
        assertEquals(pictureJson(), preview.deposits.single().imagesJson)
    }

    @Test fun attachingToNewRecordDoesNotReplaceUnrelatedMaturityPictures() {
        val before = state().copy(deposits=listOf(yearly().copy(id="old",imagesJson=pictureJson())))
        val after = Engine.invest(before, deposit(), currentDate=date)
        val annotated = Pictures.annotate(before, after, "[]")
        assertEquals(pictureJson(), annotated.deposits.first().imagesJson)
        assertEquals(pictureJson(), annotated.transactions.first { it.id == "close:old" }.imagesJson)
    }
    @Test fun futureWithdrawalIsReservedAndDeletionOnlyReleasesReservation() {
        val start = LocalDate.now().plusDays(10)
        val s = Engine.change(state(), "a", -200_000, "MANUAL_EXPENSE", start.toString(), "未来取款")
        assertEquals(10_000_000L, s.cash()); assertEquals(200_000L, s.reserved()); assertEquals(9_800_000L, s.availableCash())
        assertEquals(s, BackupCodec.parse(BackupCodec.export(s)).ledger())
        assertEquals(10_000_000L, Engine.deleteTransaction(s, s.transactions.single().id).cash())
        assertEquals(9_800_000L, Engine.settle(s, start).cash())
    }
    @Test fun backupRejectsMissingFuturePurchaseAndTransferPartner() {
        val s = Engine.createDeposit(state(), deposit().copy(startDate = "2026-11-01"), currentDate = date)
        throws { BackupCodec.export(s.copy(transactions = emptyList())) }
        val transfer = Engine.transfer(state(), "a", "b", 100, "2026-11-01", "", date)
        throws { BackupCodec.export(transfer.copy(transactions = transfer.transactions.take(1))) }
    }

}
