package com.grassland.intelligence.mediaplatform;

/**
 * 视频分析平台级 Qwen 提示词（移植 legacy {@code providers/qwen-provider.ts} 的
 * {@code QWEN_ANALYSIS_PROMPT} 与 {@code QWEN_RECREATION_PROMPT}，逐字对齐）。
 *
 * <p>各媒体平台（Bilibili / Douyin）Java 分析路径**共用同一份提示词**——legacy 侧 douyin/bilibili 本就都走
 * {@code analyzeVideoContent}（同 prompt），此处保持单一副本，杜绝两份提示词漂移。
 *
 * <p>{@link #analysis()} 用于内容提取（6 字段 JSON），{@link #recreation()} 用于复刻分镜场景（scenes JSON）。
 * 与 video_url content part 一并发给视频理解模型。
 */
public final class VideoAnalysisPrompts {

    private VideoAnalysisPrompts() {}

    /** 视频内容提取提示（输出 6 字段：video_captions/video_script/characters_description/voice_description/props_description/scene_description）。 */
    public static final String ANALYSIS = """
            你是一位专业的视频内容分析师。请按照以下步骤仔细分析视频内容，然后返回结构化的 JSON 结果。

            ## 分析步骤

            1. **整体浏览**：先观看视频全貌，把握主题、时间线和整体氛围
            2. **逐段细看**：按时间顺序逐段分析，记录每个关键时刻的画面、声音、文字信息
            3. **归纳整理**：将观察到的信息按下方字段分类整理，确保描述具体、准确、有细节

            ## 输出字段说明（每个字段都必须认真填写，宁可详细不可敷衍）

            ### video_captions
            视频中出现的所有语音和文字内容的如实记录，包括字幕、台词、旁白、对话、背景解说、标题、贴纸文字、画面中的招牌/标签等。按时间顺序逐条转录，不要省略或总结。
            格式：每行一条，带时间戳。示例：
            [00:01] 大家好，今天给大家推荐一家我私藏很久的面馆
            [00:03] 今日推荐：红烧牛肉面
            [00:06] 他家的红烧牛肉面，汤底是每天现熬八个小时的
            [00:08] 这碗面真的绝了
            [00:15] 地址：xx路xx号

            ### video_script
            根据视频内容整理成专业分镜脚本，可直接用于视频生成工具。必须按分镜格式输出为 JSON 数组，每个镜头包含以下字段：
            - shot_number：镜号（从 1 递增）
            - shot_type：镜别（特写/中景/全景/远景/俯拍/仰拍等）
            - visual_content：画面内容描述（人物位置、动作、表情、画面主体）
            - camera_movement：机位与运镜（固定/推/拉/摇/移/跟拍/航拍等）
            - dialogue_narration：台词或旁白内容（无台词时写"无"）
            - on_screen_text：字幕或画面中的文字（无时写"无"）
            - duration_seconds：预估镜头时长（秒）
            - notes：备注（转场方式、特效、氛围提示等）

            至少包含 3 个镜头。示例：
            [
              {
                "shot_number": 1,
                "shot_type": "中景",
                "visual_content": "年轻女生坐在面馆靠窗位置，面前摆着一碗红烧牛肉面",
                "camera_movement": "固定机位，缓慢推进",
                "dialogue_narration": "大家好，今天给大家推荐一家我私藏很久的面馆",
                "on_screen_text": "今日推荐：红烧牛肉面",
                "duration_seconds": 5,
                "notes": "暖色调开场，自然过渡到下一镜头"
              }
            ]

            ### characters_description
            视频中出现的每个人物的详细描述。每个角色单独一段，按后续人物三视图设定的需求来写，尽量覆盖正面/侧面/背面都能看到的外观信息，包括发型、脸型、身材、服装、配饰、姿态、角色定位。至少 20 字描述每个人物。
            示例：
            画面中出现一位年轻女性，长发扎马尾，穿白色T恤搭配牛仔裤，正在品尝面条，表情满足，应该是视频的博主/探店达人。
            背景中有一位穿围裙的厨师，中年男性，正在后厨操作台前忙碌。

            ### voice_description
            视频中的声音特征分析。包含：说话人的音色（清亮/低沉/温柔/磁性等）、语速（快/慢/适中）、语调变化、情感色彩（兴奋/平静/感叹等）、是否有背景音乐（风格、节奏）。至少 30 字。
            示例：
            博主以清亮活泼的女声解说，语速偏快，语气充满热情和真实感，多处感叹句表达惊喜。背景配了一首轻快的日系吉他曲，节奏明快，音量适中不抢话。

            ### props_description
            视频中出现的所有值得注意的道具、物品、产品的详细描述。每个物品单独一行，包含外观、尺寸感、颜色、摆放位置、使用状态等细节。至少 15 字描述每个物品。不要遗漏关键道具；即使道具信息混在场景描述里，也要单独提炼到这个字段。
            示例：
            一个白瓷大碗装的红烧牛肉面，碗口约20cm，汤色红亮浓郁，上面铺满大块牛肉和翠绿的葱花，还配有几片白菜
            桌上摆着一杯冰可乐，玻璃杯壁上有水珠，旁边放着一双木质筷子和不锈钢勺子

            ### scene_description
            视频中出现的所有场景的详细描述。每个场景单独一段，包含地点类型、环境布置、光线条件、画面构图、氛围感受等。至少 25 字描述每个场景。要和 props_description 配合：这里重点写场景整体，不要把关键道具遗漏到两个字段都没有。
            示例：
            面馆内部，装修偏日式简约风格，木质桌椅整齐排列，墙上挂着菜单木板，暖黄色灯光营造温馨氛围，画面构图居中对准桌面，虚化背景突出食物

            ## 质量要求
            - 6 个 key 都必须返回，不能缺少 key
            - 每个字段都必须填写，不允许留空或写"无"
            - 描述要具体、有画面感，避免"一个人"、"一些东西"这样模糊的表述
            - 时间戳尽量准确，与视频实际进度对应
            - 如果某个字段内容确实很少（如视频全程无声），也如实说明，不要杜撰
            - characters_description 要尽量满足后续人物三视图设定使用
            - voice_description 要聚焦人物主音色/旁白音色、语速、语气、情绪

            你必须且只能返回以下格式的合法 JSON 对象，不要返回任何其他文字：
            {
              "video_captions": "逐条转录内容，每行一条带时间戳",
              "video_script": [{ "shot_number": 1, "shot_type": "中景", "visual_content": "画面描述", "camera_movement": "运镜方式", "dialogue_narration": "台词或旁白", "on_screen_text": "字幕文字", "duration_seconds": 5, "notes": "备注" }],
              "characters_description": "人物描述，每行一条",
              "voice_description": "声音特征描述",
              "props_description": "道具物品描述，每行一条",
              "scene_description": "场景描述，每行一条"
            }""";

    /** 视频复刻分镜场景提示（输出 scenes 数组 + overall_style）。 */
    public static final String RECREATION = """
            你是一位专业的短视频分镜分析师。请仔细观看视频，按镜头变化将视频拆分为多个独立场景，每个场景输出结构化信息。

            ## 分析步骤

            1. **整体浏览**：先观看视频全貌，把握主题、节奏和整体视觉风格
            2. **识别场景切换**：根据镜头变化（景别切换、场景转换、动作变化）划分独立场景
            3. **逐场景分析**：对每个场景详细记录画面构成、人物、动作、声音和环境

            ## 每个场景的字段说明

            ### shot_description（镜头描述）
            描述该场景的视觉构图：景别（特写/中景/全景）、摄像机角度、运镜方式（推拉摇移）、画面主体位置。
            示例：中景正面视角，镜头缓慢推进，画面居中对准桌上的面碗，背景虚化

            ### character_description（人物描述）
            该场景中出现的人物外貌、穿着、表情、姿态。每个角色至少 15 字。
            示例：一位年轻女性博主，长发披肩，穿白色短袖T恤，面带微笑坐在桌前

            ### action_movement（动作/运动）
            人物的动作和画面中物体的运动变化。描述动态内容。
            示例：用筷子夹起一块牛肉，放入嘴中，表情满足地点头

            ### dialogue_voiceover（对白/旁白）
            该场景中的语音内容，包括旁白解说、对话、字幕文字。如实记录，不总结。
            示例：这家的红烧牛肉面真的太好吃了，汤底浓郁，牛肉也超大块

            ### scene_environment（场景环境）
            场景的物理环境：地点、装修风格、光线条件、色调氛围、可见道具。
            示例：面馆内部，日式原木装修风格，暖黄色吊灯，背景有木质菜单板和绿植装饰

            ## 质量要求
            - 典型短视频拆分为 3-8 个场景
            - 每个场景必须视觉上相对独立，能作为独立的参考画面
            - shot_description 要足够详细，可以直接作为 AI 绘图的提示词
            - 描述要具体有画面感，避免模糊表述

            你必须且只能返回以下格式的合法 JSON 对象，不要返回任何其他文字：
            {
              "scenes": [
                {
                  "shot_description": "镜头描述",
                  "character_description": "人物描述",
                  "action_movement": "动作描述",
                  "dialogue_voiceover": "对白旁白",
                  "scene_environment": "环境描述"
                }
              ],
              "overall_style": "整体视觉风格和色调氛围的总结"
            }""";

    public static String analysis() {
        return ANALYSIS;
    }

    public static String recreation() {
        return RECREATION;
    }
}
