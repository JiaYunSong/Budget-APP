package com.local.deposittracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.local.deposittracker.MainActivity
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class AppFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun waitText(text: String) = ui.waitUntil(90_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun fill(label: String, value: String) {
        ui.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(value)
    }
    @Test fun welcomeCreateAccountDepositAndNavigate() {
        waitText("开始使用"); ui.onNodeWithText("开始使用").performClick()
        waitText("创建活期账户")
        fill("账户名称 *", "工商银行")
        fill("初始余额（元）*", "100000")
        ui.onNodeWithText("保存").performClick()
        waitText("¥ 100,000.00")
        ui.onNodeWithContentDescription("新增资产或规则").performClick()
        listOf("一次性存款", "一次性取款", "固定收入", "投资", "自动月存", "账户间转账").forEach { ui.onNodeWithText(it).assertExists() }
        ui.onNodeWithText("投资").performClick()
        fill("产品名称 *", "三个月定期")
        fill("本金（元）*", "50000")
        fill("到期日期 YYYY-MM-DD", LocalDate.now().plusMonths(3).toString())
        ui.onNodeWithText("保存").performClick()
        ui.onAllNodesWithText("¥ 100,000.00").onFirst().assertIsDisplayed()
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("三个月定期"))
        ui.onNodeWithText("三个月定期").assertIsDisplayed()
        ui.onNodeWithText("日历", useUnmergedTree = true).performClick()
        waitText("月视图"); ui.onNodeWithText("年视图").performClick()
        waitText("全年新增存款（已入账）")
        ui.onNodeWithText("全部流水").performClick()
        waitText("流水筛选")
        ui.onNodeWithText("存款", useUnmergedTree = true).performClick()
        waitText("定期资产"); ui.onNodeWithText("三个月定期").performClick()
        waitText("转存历史"); ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithContentDescription("新增资产或规则").performClick()
        ui.onNodeWithText("自动月存").performClick()
        fill("每月金额（元）*", "100")
        fill("每月几日（1–31）*", LocalDate.now().dayOfMonth.toString())
        ui.onNodeWithText("保存").performClick()
        waitText("收入 / 月存"); ui.onNodeWithText("收入 / 月存").performClick()
        waitText("每月工资结余"); ui.onNodeWithText("删除规则").performClick()
        waitText("删除自动月存规则？"); ui.onAllNodesWithText("删除规则").onLast().performClick()
        waitText("为储蓄建立节奏")
        ui.onNodeWithContentDescription("选择统计账户").performClick()
        waitText("统计账户"); ui.onNodeWithText("工商银行").assertExists()
        ui.onNodeWithText("完成").performClick()
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        waitText("导出完整备份（JSON）")
        ui.onNodeWithText("导出完整备份（JSON）").assertExists()
    }
}
