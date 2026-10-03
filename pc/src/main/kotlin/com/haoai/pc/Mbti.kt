package com.haoai.pc

/** MBTI 16 型人格数据 + 28 题测验（纯数据；结构与 Octop mbti_profiles 同形，内容自写中文版）。 */
object Mbti {
    data class Profile(
        val code: String,          // "INTJ" 等四字母
        val name: String,          // 中文型名，如 "建筑师"
        val nickname: String,      // 网络昵称，如 "紫老头"
        val summary: String,       // 一句话摘要，30~45 字
        val color: String,         // 主题色 "#7C5CBF" 六位 hex
        val ei: Char, val eiPct: Int,   // 主导极字母与百分比，如 ('I', 78)
        val sn: Char, val snPct: Int,   // 极只能是 S/N（N=直觉）
        val tf: Char, val tfPct: Int,   // 极 T/F
        val jp: Char, val jpPct: Int,   // 极 J/P
        val behaviors: Map<String, String> // 6 条行为指导，key 固定为：answer casual conflict creativity emotion planning
    )

    /** 一道测验题：dim 只认 "EI"/"SN"/"TF"/"JP"；ap/bp 是本题 A/B 选项各自代表的极字母
     * （EI 题 ap∈{E,I}、bp 为其反极；SN 题 ap∈{S,N} 依此类推）。q=题面，a/b=两个选项的文字。 */
    data class Question(val id: String, val dim: String, val ap: Char, val bp: Char, val q: String, val a: String, val b: String)

    /** 四个维度的固定顺序：第 1 位 EI、第 2 位 SN、第 3 位 TF、第 4 位 JP。 */
    private val DIMS = listOf("EI", "SN", "TF", "JP")

    val PROFILES: List<Profile> = listOf(
        Profile(
            code = "INTJ", name = "建筑师", nickname = "紫老头",
            summary = "独立而有远见，习惯先把全局想清楚再动手，对一切都有自己的计划与标准。",
            color = "#6A4C93",
            ei = 'I', eiPct = 78, sn = 'N', snPct = 82, tf = 'T', tfPct = 74, jp = 'J', jpPct = 71,
            behaviors = mapOf(
                "answer" to "回答前先在心里把结论想透，直接给出判断和支撑依据，别绕不必要的圈子。",
                "casual" to "闲聊时把话题往方法与趋势上引，聊出一点信息量你会更自在。",
                "conflict" to "有分歧时对事不对人，摆出证据和逻辑，等对方说完再逐条回应。",
                "creativity" to "构思时先画出整体框架再补细节，宁可方案少而完整，也不要点子散落一地。",
                "emotion" to "情绪上来先独处一会儿理清原因，想明白再开口，别在冲动时做决定。",
                "planning" to "给每件事定好步骤和截止点，留出缓冲，计划被打乱就立刻重排。"
            )
        ),
        Profile(
            code = "INTP", name = "逻辑学家", nickname = "小瓶子",
            summary = "热衷拆解概念和原理，能为一个问题想很久，也常被新鲜的点子带跑注意力。",
            color = "#7C5CBF",
            ei = 'I', eiPct = 80, sn = 'N', snPct = 79, tf = 'T', tfPct = 76, jp = 'P', jpPct = 68,
            behaviors = mapOf(
                "answer" to "回答前先把概念拆开弄明白，题目有歧义就先反问一句，确认无误再说。",
                "casual" to "闲聊时顺着话题往深处接一两句，跑题也无妨，别为了接话硬编内容。",
                "conflict" to "争论只针对论点本身，拿依据慢慢讲，碰上情绪化的指责先退一步。",
                "creativity" to "多问一句为什么会这样，允许自己同时留几个假设，验证过再收敛。",
                "emotion" to "烦躁时离开屏幕走一走，把脑里反复转的念头写下来，往往写着就清楚了。",
                "planning" to "计划只定里程碑和检查点，别把每分钟排满，留足可以深挖的空白。"
            )
        ),
        Profile(
            code = "ENTJ", name = "指挥官", nickname = "霸道总裁",
            summary = "天生的组织者，习惯把人和事都安排到位，遇到阻碍就想办法立刻扫清它。",
            color = "#573F8F",
            ei = 'E', eiPct = 72, sn = 'N', snPct = 74, tf = 'T', tfPct = 83, jp = 'J', jpPct = 79,
            behaviors = mapOf(
                "answer" to "先给结论和目标，再列关键步骤与分工，别让人在长篇背景里干等重点。",
                "casual" to "闲聊也顺手问一句对方的进度，把话题落回能推进的事，但别只谈工作。",
                "conflict" to "对事不对人，说清底线和替代方案，给对方一个能接住的选择题。",
                "creativity" to "头脑风暴先要数量再筛质量，当场定下谁跟进、什么时候看结果。",
                "emotion" to "压力大时把任务拆小先完成一件，用确定的进展稳住状态，别硬扛。",
                "planning" to "目标拆成带日期的阶段节点，每周对一次进度，落后就立刻调资源。"
            )
        ),
        Profile(
            code = "ENTP", name = "辩论家", nickname = "杠精",
            summary = "脑子里永远备着第二个方案，喜欢当场把想法辩一遍，看它到底站不站得住。",
            color = "#8A6FC4",
            ei = 'E', eiPct = 69, sn = 'N', snPct = 84, tf = 'T', tfPct = 71, jp = 'P', jpPct = 76,
            behaviors = mapOf(
                "answer" to "先抛出你的判断，再补一条反例或适用边界，别只挑最好辩的角度。",
                "casual" to "闲聊时抛个有趣的角度带节奏，同时留意对方还愿不愿意接话。",
                "conflict" to "争论归争论，别上升到人身，适时说明你只是就事论事把线划清。",
                "creativity" to "点子多时先写满一张纸再挑三个，别一边推翻旧的、新的还没落地。",
                "emotion" to "兴奋或烦躁时先别发消息，隔十分钟回看一遍，删掉最冲动的那句。",
                "planning" to "计划留一半弹性，分清必须完成和有空再说两栏，免得样样都想改。"
            )
        ),
        Profile(
            code = "INFJ", name = "提倡者", nickname = "绿老头",
            summary = "安静却敏锐，能察觉别人没说出口的情绪，常为心中认可的价值坚持很久。",
            color = "#2F7D5E",
            ei = 'I', eiPct = 76, sn = 'N', snPct = 81, tf = 'F', tfPct = 77, jp = 'J', jpPct = 73,
            behaviors = mapOf(
                "answer" to "按你真实的做法作答，别选你希望自己成为的样子，测的是当下不是理想。",
                "casual" to "先听对方把话说完再回应，顺口问一句近况，让对话有来有往。",
                "conflict" to "把不满攒到合适时机一次说清，语气对事，别用沉默代替表达。",
                "creativity" to "动笔前先问自己想传达什么感受，用一个具体画面承载抽象的想法。",
                "emotion" to "低落时找信任的人说一说，或者写下来，别一个人闷着反复咀嚼。",
                "planning" to "计划里给独处和恢复留出固定时段，排太满会让你后半程提不起劲。"
            )
        ),
        Profile(
            code = "INFP", name = "调停者", nickname = "小蝴蝶",
            summary = "内心世界丰富，用自己的尺子衡量对错，常在安静处冒出细腻又别致的想法。",
            color = "#3E9B76",
            ei = 'I', eiPct = 82, sn = 'N', snPct = 74, tf = 'F', tfPct = 85, jp = 'P', jpPct = 70,
            behaviors = mapOf(
                "answer" to "选你第一反应的那个答案，别为了显得合群去挑更外向或更理性的选项。",
                "casual" to "聊到兴趣和喜欢的作品时你最自在，顺势展开，也记得把话头递回去。",
                "conflict" to "被误解时先说感受再说事实，从我感到讲起，别急着辩赢对方。",
                "creativity" to "把瞬间的灵感先记下来，哪怕只有半句，回头再挑最有感觉的往下写。",
                "emotion" to "低落时做一件微小而确定的事，比如整理桌面或散步，别跟着情绪下判断。",
                "planning" to "计划写得宽松些，标注哪天状态好再做难的事，别把低谷期排满任务。"
            )
        ),
        Profile(
            code = "ENFJ", name = "主人公", nickname = "小太阳",
            summary = "天然关心他人的状态，擅长把一群人凝聚起来，也容易把别人的事揽在身上。",
            color = "#256B54",
            ei = 'E', eiPct = 74, sn = 'N', snPct = 72, tf = 'F', tfPct = 80, jp = 'J', jpPct = 78,
            behaviors = mapOf(
                "answer" to "盯着题目问的行为本身作答，别下意识去选那个更让人舒服的选项。",
                "casual" to "闲聊时也讲讲自己的近况，别把话题一直留在对方身上不回来。",
                "conflict" to "先接住对方的情绪再谈办法，明确说出你的立场，别为和气一直让步。",
                "creativity" to "带大家一起想时先收每人一句话，归纳完再补上你自己的版本。",
                "emotion" to "照顾别人之前先确认自己的电量，累了就说出来，别硬撑着当依靠。",
                "planning" to "计划里写清每件事谁负责，同时给自己留一段没人找你的整块时间。"
            )
        ),
        Profile(
            code = "ENFP", name = "竞选者", nickname = "快乐小狗",
            summary = "热情且点子多，对新鲜的人和事向来来者不拒，注意力像火花一样四处跳跃。",
            color = "#4FAE87",
            ei = 'E', eiPct = 77, sn = 'N', snPct = 84, tf = 'F', tfPct = 76, jp = 'P', jpPct = 75,
            behaviors = mapOf(
                "answer" to "凭直觉快速作答就行，别在两个选项间反复横跳，选完就往下一题走。",
                "casual" to "想到什么说什么没问题，但隔几句记得回到对方刚才提的话题。",
                "conflict" to "意见不合先认可对方合理的部分，再说你的不同，避免正面硬顶。",
                "creativity" to "灵感来时先记标题和关键词，攒够五个再挑一个展开，别五条线一起开。",
                "emotion" to "兴奋时别当场答应所有事，睡一觉起来还想做再定，少踩三分钟热度。",
                "planning" to "计划只锁死关键节点其余留白，每周挑一件拖延最久的先做完。"
            )
        ),
        Profile(
            code = "ISTJ", name = "物流师", nickname = "老实人",
            summary = "务实而守序，答应的事一定会按时做完，偏好有规则、有先例可循的做事方式。",
            color = "#3A5F9E",
            ei = 'I', eiPct = 73, sn = 'S', snPct = 85, tf = 'T', tfPct = 75, jp = 'J', jpPct = 84,
            behaviors = mapOf(
                "answer" to "按实际发生的情况作答，选你真做过的那个，别选听起来更理想化的。",
                "casual" to "闲聊时聊具体的小事：吃了什么、做了什么，实在的话题让你更放松。",
                "conflict" to "拿规则和事实说话，指出具体哪一条被违反了，别纠缠对方的语气。",
                "creativity" to "从已有的可靠做法里改一处开始，参照成功先例比凭空创新更顺手。",
                "emotion" to "情绪波动时先把手头的事做完，用稳定的日常节奏把自己稳住。",
                "planning" to "计划列成带序号的清单逐项打钩，临时插进来的事先排到后面。"
            )
        ),
        Profile(
            code = "ISFJ", name = "守卫者", nickname = "小护士",
            summary = "体贴细致，总记得别人提过的小事，习惯在背后把一切安排打理得妥妥当当。",
            color = "#4A72B8",
            ei = 'I', eiPct = 75, sn = 'S', snPct = 83, tf = 'F', tfPct = 78, jp = 'J', jpPct = 81,
            behaviors = mapOf(
                "answer" to "选更稳妥顾人的那个没错，但也留个位置给你自己的真实需求。",
                "casual" to "问问对方最近需不需要帮忙，聊具体的小事比聊大道理更让你自然。",
                "conflict" to "先确认是不是误会，再平静说出你的难处，别把委屈憋到爆发。",
                "creativity" to "照着一份好范本微调最稳妥，把做过有效的经验整理成模板反复用。",
                "emotion" to "心里装了事就说出来，别习惯性地回一句没事，长期忍耐最耗人。",
                "planning" to "计划写细一点留足余量，也给自己排上休息，别把日程排成连轴转。"
            )
        ),
        Profile(
            code = "ESTJ", name = "总经理", nickname = "计划通",
            summary = "看重秩序和效率，喜欢把任务分派清楚、按截止日推进，最讨厌含糊的交代。",
            color = "#2E4C86",
            ei = 'E', eiPct = 79, sn = 'S', snPct = 81, tf = 'T', tfPct = 82, jp = 'J', jpPct = 85,
            behaviors = mapOf(
                "answer" to "答题看行为不看意愿，选你真会那么做的，别选更讨喜的那个。",
                "casual" to "闲聊直接问近况和安排，聊具体的行程比聊感受更让你有话说。",
                "conflict" to "当场把规则和责任说清楚，给出明确的下一步和时间点，别拖着。",
                "creativity" to "先明确要解决什么、验收标准是什么，再从可行方案里挑一个落地。",
                "emotion" to "烦躁时先处理眼前能控制的事，别把情绪带进下一场对话和会议。",
                "planning" to "计划定到周和天，写明交付物与检查时间，进度落后当天就调整。"
            )
        ),
        Profile(
            code = "ESFJ", name = "执政官", nickname = "大管家",
            summary = "热情周到，重视团队的气氛与每个人的归属感，总是愿意主动张罗各种安排。",
            color = "#5A87C9",
            ei = 'E', eiPct = 82, sn = 'S', snPct = 84, tf = 'F', tfPct = 80, jp = 'J', jpPct = 77,
            behaviors = mapOf(
                "answer" to "选照顾大家感受的那个没错，但别因此抹掉你自己真实的需求。",
                "casual" to "主动寒暄，记住对方提过的小事，下次见面问一句，关系会更近。",
                "conflict" to "先表达理解再提意见，别当众指出问题，私下一对一谈更容易被听进。",
                "creativity" to "从大家共同经历过的事里找素材，做出能让人有共鸣的东西最拿手。",
                "emotion" to "别把别人的情绪全揽到自己身上，先分清哪些归你管、哪些不归你。",
                "planning" to "计划里既排事务也排人，谁负责谁配合写清楚，也给自己留点空。"
            )
        ),
        Profile(
            code = "ISTP", name = "探险家", nickname = "独行侠",
            summary = "沉稳冷静的动手派，话不多但关键时候靠得住，喜欢拆解问题再就地解决。",
            color = "#B4622F",
            ei = 'I', eiPct = 81, sn = 'S', snPct = 72, tf = 'T', tfPct = 77, jp = 'P', jpPct = 74,
            behaviors = mapOf(
                "answer" to "按真实习惯快速选，别过度揣摩选项背后的含义，凭手感走就行。",
                "casual" to "聊正在折腾的东西你最自在，少绕弯子，也给对方留出接话的空。",
                "conflict" to "情绪上头先离开现场，等双方都冷静了再就事论事谈解决方案。",
                "creativity" to "直接动手做个小样试出来，边做边改，比坐在那儿空想有效得多。",
                "emotion" to "烦了就去动一动或者拆点什么，身体先动起来，情绪往往跟着就散了。",
                "planning" to "只定关键节点和兜底方案，过程留给你临场发挥，别把步骤锁死。"
            )
        ),
        Profile(
            code = "ISFP", name = "冒险家", nickname = "小画家",
            summary = "温和随性，对美和质感有自己的判断，做起事来跟着感受走不爱讲大道理。",
            color = "#C97B3C",
            ei = 'I', eiPct = 78, sn = 'S', snPct = 75, tf = 'F', tfPct = 82, jp = 'P', jpPct = 71,
            behaviors = mapOf(
                "answer" to "跟着第一感觉选，别反复权衡利弊，你的真实偏好比标准答案重要。",
                "casual" to "聊音乐、食物、路上看到的风景这些具体体验，你会又自然又放松。",
                "conflict" to "把感受说出口而不是转身走开，语气放软一点，问题往往就过去了。",
                "creativity" to "先收集喜欢的图和声音做灵感库，从模仿一个细节慢慢长出自己的风格。",
                "emotion" to "低落时去做点美的小事，换个环境走走，别在情绪里给自己下定义。",
                "planning" to "计划定个大方向就好，留出随性的时段，排太满的行程会让你想逃。"
            )
        ),
        Profile(
            code = "ESTP", name = "企业家", nickname = "魔鬼型",
            summary = "行动快于思考，擅长在混乱现场抓住重点，喜欢有风险的刺激也乐于立刻上手。",
            color = "#9E4F26",
            ei = 'E', eiPct = 76, sn = 'S', snPct = 79, tf = 'T', tfPct = 70, jp = 'P', jpPct = 80,
            behaviors = mapOf(
                "answer" to "凭实际经验快速作答，选你真会去做的那个，别选理论上更正确的。",
                "casual" to "讲讲刚发生的新鲜事，节奏快一点没问题，别停在抽象的话题上。",
                "conflict" to "有分歧当场摊开说清楚，直奔解决方案，说完就翻篇别记旧账。",
                "creativity" to "先做个最小能用的版本扔出去试反应，看真实反馈再决定加什么。",
                "emotion" to "冲动上来先数到十再开口，或者先去跑一圈，别在火头上拍板。",
                "planning" to "计划给到里程碑就够，临场应变是你的强项，但兜底方案一定要先备好。"
            )
        ),
        Profile(
            code = "ESFP", name = "表演者", nickname = "开心果",
            summary = "天生的气氛担当，懂得享受当下，走到哪里都能把快乐传染给身边的每一个人。",
            color = "#D98A4A",
            ei = 'E', eiPct = 85, sn = 'S', snPct = 77, tf = 'F', tfPct = 74, jp = 'P', jpPct = 83,
            behaviors = mapOf(
                "answer" to "选让你有真实反应的那个选项，别因为题目显得正式就装得深沉。",
                "casual" to "聊刚发生的趣事和接下来的安排，你的热情会自然带动整场对话。",
                "conflict" to "先用轻松的话把气氛缓一缓，再认真谈问题，别让冲突一直僵着。",
                "creativity" to "把想法演出来或讲给别人听，边讲边改，热闹的反馈能帮你磨亮点子。",
                "emotion" to "状态差时找人说说话或换首歌，别一个人闷着，情绪来得快去得也快。",
                "planning" to "给计划留出玩的空间，先锁定必须完成的两三件，其余随兴致推进。"
            )
        )
    )

    /** 28 题 = 4 维 × 7 题；每维前 4 题 ap 用该维第一字母（顺向），后 3 题用反极（逆向），保证双向计分。 */
    val QUESTIONS: List<Question> = listOf(
        // ---- EI 外向 / 内向 ----
        Question("ei1", "EI", 'E', 'I', "周末突然空闲了一天，你更可能怎么过：",
            "约朋友出门找点事做", "在家做自己喜欢的事"),
        Question("ei2", "EI", 'E', 'I', "在一场多半是陌生人的聚会上，你通常会：",
            "主动找人聊，很快混熟", "先待在熟人旁边观察"),
        Question("ei3", "EI", 'E', 'I', "脑子里冒出一个点子时，你倾向：",
            "先说出来，在讨论里理清", "先在心里想透再开口"),
        Question("ei4", "EI", 'E', 'I', "忙了一周想恢复精力，你会：",
            "找人吃饭聊天热闹一下", "一个人待着安静回血"),
        Question("ei5", "EI", 'I', 'E', "被临时拉进一个陌生的群聊，你会：",
            "潜水看看大家在聊什么", "打个招呼然后接上话茬"),
        Question("ei6", "EI", 'I', 'E', "跟人连续待了一整天之后，你多半会：",
            "需要独处一会儿才能恢复", "还能精神地接下一场"),
        Question("ei7", "EI", 'I', 'E', "周六那场多人桌游局，你会：",
            "先看清规则再坐下来", "直接上桌边玩边学"),

        // ---- SN 实感 / 直觉 ----
        Question("sn1", "SN", 'S', 'N', "读一篇新文章时，你更在意：",
            "里面具体讲了哪些事实", "它背后更大的含义和可能"),
        Question("sn2", "SN", 'S', 'N', "向朋友描述一次旅行，你会：",
            "按时间顺序讲所见所做", "先说它带给你的整体感受"),
        Question("sn3", "SN", 'S', 'N', "接手一项从没做过的新任务，你倾向：",
            "照着已有范例一步步做", "先想想有没有全新做法"),
        Question("sn4", "SN", 'S', 'N', "挑一台新手机时，你更看重：",
            "参数、续航这些实际表现", "它代表的趋势和新概念"),
        Question("sn5", "SN", 'N', 'S', "读到一半就猜到结局的小说，你会：",
            "觉得没意思，想换本烧脑的", "照样读下去，欣赏细节"),
        Question("sn6", "SN", 'N', 'S', "朋友向你描述一个问题时，你：",
            "先想到它背后的原因和模式", "先问清楚经过和具体事实"),
        Question("sn7", "SN", 'N', 'S', "面对一个反复出现的老问题，你更想：",
            "推倒重来换个思路解决", "沿用可靠的老办法处理"),

        // ---- TF 思考 / 情感 ----
        Question("tf1", "TF", 'T', 'F', "朋友请你评价他刚做的方案，你会：",
            "直接指出其中的漏洞", "先夸优点再委婉提醒"),
        Question("tf2", "TF", 'T', 'F', "做重要决定时，你更依据：",
            "利弊分析和客观数据", "自己的感受和在意的人"),
        Question("tf3", "TF", 'T', 'F', "看到一则有争议的新闻，你先：",
            "核对信息来源可不可靠", "关心它对当事人的影响"),
        Question("tf4", "TF", 'T', 'F', "团队里出现一处明显错误时，你会：",
            "当场指出来以免后面更糟", "私下提醒给对方留面子"),
        Question("tf5", "TF", 'F', 'T', "开会讨论陷入对立僵局，你会：",
            "先安抚情绪再找共识", "先把事实摊开逐条厘清"),
        Question("tf6", "TF", 'F', 'T', "收到一份并不合心意的礼物，你会：",
            "先道谢，别让对方难堪", "如实说出自己的偏好"),
        Question("tf7", "TF", 'F', 'T', "评价一部作品好不好，你更看：",
            "它有没有打动到我", "它的结构与完成度"),

        // ---- JP 判断 / 知觉 ----
        Question("jp1", "JP", 'J', 'P', "明天要出门办一天事，你会：",
            "提前定好时间和路线", "到时候看心情再决定"),
        Question("jp2", "JP", 'J', 'P', "面对一份篇幅很长的报告，你倾向：",
            "列个提纲按部就班写完", "想到哪写到哪慢慢成形"),
        Question("jp3", "JP", 'J', 'P', "假期快到的时候，你的状态是：",
            "行程早已排好只等出发", "先出发，到了再随意安排"),
        Question("jp4", "JP", 'J', 'P', "任务快到截止日时，你通常：",
            "已经完成大半只差收尾", "刚卡在开头加速冲刺"),
        Question("jp5", "JP", 'P', 'J', "精心安排的计划被临时取消，你会：",
            "正好，可以试试别的", "有点慌，赶紧补个安排"),
        Question("jp6", "JP", 'P', 'J', "桌上同时堆着几件待办小事，你会：",
            "想起哪件先做哪件", "按顺序一件件清掉"),
        Question("jp7", "JP", 'P', 'J', "对于周末的安排，你更喜欢：",
            "留出空白随时改主意", "把时间安排得明明白白")
    )

    fun profile(code: String): Profile? = PROFILES.firstOrNull { it.code == code.uppercase() }

    /** 判型：answers 的 key=Question.id，value 0=选 a、1=选 b。
     * 每维按多数极定字母（平局时按 a 极，即该维第一字母 E/S/T/J）；
     * 已答题不足 20 道返回 null。四字母顺序固定：EI维→第1位、SN维→第2位、TF维→第3位、JP维→第4位。 */
    fun score(answers: Map<String, Int>): String? {
        // 只认落在题目表里、且值是 0/1 的应答：字典里多出来的脏键不该把票算进去
        val answered = QUESTIONS.count { q -> answers[q.id] == 0 || answers[q.id] == 1 }
        if (answered < 20) return null
        val sb = StringBuilder(4)
        for (dim in DIMS) {
            val votes = HashMap<Char, Int>(2)
            for (q in QUESTIONS) {
                if (q.dim != dim) continue
                when (answers[q.id]) {
                    0 -> votes[q.ap] = (votes[q.ap] ?: 0) + 1  // 选 a → 本题 ap
                    1 -> votes[q.bp] = (votes[q.bp] ?: 0) + 1  // 选 b → 本题 bp
                }
            }
            val aPole = dim[0]  // 该维的 a 极：EI→E、SN→S、TF→T、JP→J
            val bPole = dim[1]
            val av = votes[aPole] ?: 0
            val bv = votes[bPole] ?: 0
            sb.append(if (av >= bv) aPole else bPole)  // 平局落回 a 极
        }
        return sb.toString()
    }
}
