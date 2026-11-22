package io.github.r2r3k.fengmingharness.core

/**
 * 提示词模板：点一下就把整段话填进输入框，省得每次自己敲。
 * 都是给"自己用"的话术，可以直接改。
 */
object PromptTemplates {

    data class Template(val title: String, val text: String)

    data class Group(val name: String, val items: List<Template>)

    val groups: List<Group> = listOf(
        Group(
            name = "读文档 / 论文",
            items = listOf(
                Template("先讲人话", "先用三句话讲清楚这篇文档在说什么，不要术语。然后再逐条展开。"),
                Template("抓结论", "这份文档的核心结论是什么？作者用什么证据支撑？有哪些明显的限制或没验证的地方？"),
                Template("挑毛病", "站在审稿人的角度，挑出这份文档最可能被质疑的 3 个地方，并说明为什么。"),
                Template("提炼可复用的东西", "把这份文档里我能直接拿走用的方法/数据/结论列出来，每条写一句怎么用。"),
                Template("做成汇报", "把这份文档整理成一份 5 分钟的汇报：背景、做法、结果、意义，口语化。"),
            ),
        ),
        Group(
            name = "写东西",
            items = listOf(
                Template("改得更好读", "把下面这段改得更清楚、更像人说的话，不要改观点和事实：\n\n"),
                Template("压到一半长度", "把下面这段压缩到一半以内，保留所有关键信息：\n\n"),
                Template("列大纲", "帮我把这个想法列成文章大纲，三级以内，每级一句话说明白：\n\n"),
                Template("写成邮件", "把下面这件事写成一封正式但不高冷的邮件，中文，控制在 200 字以内：\n\n"),
            ),
        ),
        Group(
            name = "分析 / 决策",
            items = listOf(
                Template("摆选项", "把这件事的可能做法列成 3~5 个选项，逐个写清优点、代价、适合什么情况下选。"),
                Template("找反例", "我这个判断最可能错在哪里？给我 3 个具体的反例或风险点。"),
                Template("分步骤", "把这件事拆成可以照着做的步骤，每步写清「做什么」和「做完了怎么算成功」。"),
                Template("算笔账", "帮我把这件事的成本和收益算清楚，缺的数据先列出来让我补。"),
            ),
        ),
        Group(
            name = "代码",
            items = listOf(
                Template("只看不给改", "先别急着改代码，告诉我这段代码在干什么、最可能出错的地方在哪。"),
                Template("给我最小改动", "用最小的改动把这个请求修好，说明改了哪几行、为什么。"),
                Template("解释报错", "解释这个报错是什么意思、可能的原因排序，以及最快验证原因的操作：\n\n"),
            ),
        ),
        Group(
            name = "用工具",
            items = listOf(
                Template("查一下电脑", "用电脑上的工具帮我查一下："),
                Template("搜我的资料", "在我本地资料里找一下有没有关于「」的内容，找到就把原文贴出来。"),
            ),
        ),
    )
}
