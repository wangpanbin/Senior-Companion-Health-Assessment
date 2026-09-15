# -*- coding: utf-8 -*-
"""
银龄伴诊 —— V2 种子数据生成器（一次性工具，生成后不入库、不进 Git）

为什么用脚本而不是手写 SQL：
  20 张表、每张业务表 >= 30 行，且订单 / 打卡 / 服药任务之间存在主外键与状态一致性约束，
  手写必然出错。脚本内所有随机数都固定 seed，保证每次生成结果完全一致（可复现）。

输出：backend/sql/V2__seed_data.sql
"""
import random
import json
import os

random.seed(20260915)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "V2__seed_data.sql")
OUT = os.path.normpath(OUT)

# ---------------------------------------------------------------- 固定密钥
BCRYPT = "$2a$10$dTfTIBtoETZnreDYKJ6Re.rgpuLth.58Y0hgjohfzPE.xBaCZZCHi"  # 明文 Nl@123456
ID_CARDS = [
    "70L8PuD5Vc1WgFJTZr9morREqE8KiNVxokziwb/I1b9yXYCh1VT5CXK8E2XwHw==",
    "jCbhDtDDdkjprbt7B4UMvB4vQstl21/bomlVFN8rxCtJIxZwBIXLN+2yziIaRA==",
    "9oF/MqvUDBBl82y5wTw0UAHBUV54rY0TAXVIjOkxW+GECsbFGJ5u06qhYLLv3Q==",
    "A44dq34FcNNhV8XxxTBj2hQt1q7NU8vA5zHaryB5i3IhBZ7QjpZeXRruJp+pxw==",
    "+HgWjfplKwaCJRy1he+r00qTlKGxNkiR35ZHPQMH3RBkjqSNvH8KcSOebU9Lsw==",
    "woqM9svE/8ugI4QuXnQUVIPNZpgXKtnAFCEtgVZW07VptFcZJSdvoZD8xu+sCA==",
    "NmoiGIc3I+q0cjSqOC6mQzuvby56fleBlzmI4/rLp5xF2xCtef4lQvn7kLOMOg==",
    "1ZfCsJd4cmNsQlQ84eNc1nVoxW5e2KxY2opyRqeSKdgjjdHptZuf9+kdfLlTuA==",
    "XCbVtxWED4/bIq331fsiKqfW5zRDChyjTauCanb7mydB40NSGN0+DjtwT/pXfg==",
    "MrtAAmauDC4kV8GypxgoBt6Y0t+TP4TTvZ7mleAZs1THX9cjTiMnjfJ4mxUr8g==",
    "KzYWYSznVuBhTMwwRci238odg0LPNEoaBgGsjl/diJxrc5cGjJAFgod8Xlalng==",
    "wov/MhNPM8gEQyBg2hEVm5flMCaMVrkBBuKXMVOrNRV7632X+4L/1/0xjYh0gQ==",
]

DISCLAIMER = "本信息仅为药品通用资料，不构成任何用药建议。具体用法用量请遵医嘱或咨询药师。"

HOSPITALS = [
    ("海南省人民医院", "海南省海口市秀英区秀华路19号"),
    ("海南医学院第一附属医院", "海南省海口市龙华区龙华路31号"),
    ("海南医学院第二附属医院", "海南省海口市龙华区椰海大道368号"),
    ("海口市人民医院", "海南省海口市美兰区人民大道43号"),
    ("海南省中医院", "海南省海口市美兰区和平北路47号"),
    ("海口市中医医院", "海南省海口市龙华区金盘路45号"),
    ("海南省妇幼保健院", "海南省海口市琼山区龙昆南路15号"),
    ("海南省肿瘤医院", "海南省海口市秀英区长滨西四街6号"),
    ("海口市第三人民医院", "海南省海口市琼山区府城镇建国路"),
    ("联勤保障部队第九二八医院", "海南省海口市龙华区金宇路1号"),
]
DEPARTMENTS = ["心血管内科", "神经内科", "内分泌科", "呼吸内科", "消化内科", "骨科",
               "眼科", "耳鼻喉科", "皮肤科", "泌尿外科", "康复医学科", "中医科",
               "老年病科", "肿瘤内科", "肾内科"]
AREAS = ["海口市美兰区", "海口市龙华区", "海口市秀英区", "海口市琼山区",
         "三亚市吉阳区", "三亚市天涯区", "儋州市那大镇", "琼海市嘉积镇"]

ELDER_NAMES = ["张德海", "李桂英", "王守义", "陈玉兰", "刘长明", "杨秀珍", "黄建国", "赵淑芬",
               "周文山", "吴凤英", "徐立本", "孙桂香", "马振声", "朱秀英", "胡耀祖", "林月娥",
               "何清明", "郭素珍", "高德全", "罗玉梅", "郑文彬", "梁淑华", "谢振华", "宋桂芳",
               "唐国栋", "许秀云", "韩立国", "冯玉珍", "邓长庚", "曹月琴"]
ELDER_GENDER = ["MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE",
                "MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE",
                "MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE",
                "MALE", "FEMALE", "MALE", "FEMALE", "MALE", "FEMALE"]

FAMILY_NAMES = ["张伟", "王芳", "李强", "刘洋", "陈静", "杨帆", "黄磊", "赵敏", "周涛", "吴丹",
                "徐鹏", "孙丽", "马超", "朱婷", "胡军", "林峰", "何静", "郭亮", "高翔", "罗敏",
                "郑凯", "梁燕", "谢东", "宋佳", "唐磊", "许娜", "韩冰", "冯刚", "邓超", "曹颖"]
FAMILY_RELATIONS = ["SON", "DAUGHTER", "SON", "DAUGHTER", "RELATIVE", "SON", "DAUGHTER", "OTHER",
                    "SON", "DAUGHTER", "SON", "DAUGHTER", "RELATIVE", "SON", "DAUGHTER", "OTHER",
                    "SON", "DAUGHTER", "SON", "DAUGHTER", "RELATIVE", "SON", "DAUGHTER", "OTHER",
                    "SON", "DAUGHTER", "SON", "DAUGHTER", "RELATIVE", "OTHER"]

COMPANION_NAMES = ["李建军", "王秀兰", "陈志强", "刘晓梅", "张国平", "杨丽娟", "黄海涛", "赵春红",
                   "周建明", "吴秀华", "徐文斌", "孙玉兰", "马晓东", "朱红梅", "胡建国", "林小燕",
                   "何志刚", "郭秀珍", "高云鹏", "罗春梅", "郑海燕", "梁志斌", "谢文静", "宋国立",
                   "唐小红", "许建平", "韩雪梅", "冯志远", "邓春华", "曹立新"]

# 60 种药品（通用名, 商品名, 规格, 剂型, 通用说明, 注意事项, 储存, 厂家, 批准文号, 处方类别, 是否常用）
MEDICINES = [
    ("苯磺酸氨氯地平片", "络活喜", "5mg×28片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起头晕、踝部水肿；如出现不适请及时就医", "遮光，密封，在30℃以下保存", "辉瑞制药有限公司", "国药准字H20051234", "PRESCRIPTION", 1),
    ("缬沙坦胶囊", "代文", "80mg×7粒", "CAPSULE", "口服，具体用法用量请遵医嘱", "可能引起头晕；用药期间注意监测血压", "密封，在25℃以下干燥处保存", "北京诺华制药有限公司", "国药准字H20040217", "PRESCRIPTION", 1),
    ("厄贝沙坦片", "安博维", "150mg×7片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起头晕、乏力；避免突然起身", "密封，在30℃以下保存", "赛诺菲制药有限公司", "国药准字H20040430", "PRESCRIPTION", 1),
    ("琥珀酸美托洛尔缓释片", "倍他乐克", "47.5mg×7片", "TABLET", "口服，整片吞服，不可咀嚼，具体用法请遵医嘱", "可能引起心动过缓；不可自行突然停药", "密封，在30℃以下保存", "阿斯利康制药有限公司", "国药准字H20140123", "PRESCRIPTION", 1),
    ("硝苯地平控释片", "拜新同", "30mg×7片", "TABLET", "口服，整片吞服，不可掰开，具体用法请遵医嘱", "可能引起面部潮红、下肢水肿", "密封，在30℃以下避光保存", "拜耳医药保健有限公司", "国药准字H20030015", "PRESCRIPTION", 1),
    ("阿托伐他汀钙片", "立普妥", "20mg×7片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起肌肉酸痛，出现肌痛请及时就医", "密封，在25℃以下保存", "辉瑞制药有限公司", "国药准字H20051408", "PRESCRIPTION", 1),
    ("瑞舒伐他汀钙片", "可定", "10mg×7片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起转氨酶升高，需定期复查肝功能", "密封，在30℃以下保存", "阿斯利康制药有限公司", "国药准字H20080215", "PRESCRIPTION", 1),
    ("辛伐他汀片", "舒降之", "20mg×7片", "TABLET", "口服，具体用法用量请遵医嘱", "避免与西柚汁同服", "遮光，密封保存", "默沙东制药有限公司", "国药准字H20050752", "PRESCRIPTION", 1),
    ("阿司匹林肠溶片", "拜阿司匹灵", "100mg×30片", "TABLET", "口服，整片吞服，具体用法请遵医嘱", "可能引起胃部不适、出血倾向；有胃溃疡史者慎用", "密封，在干燥处保存", "拜耳医药保健有限公司", "国药准字J20171021", "PRESCRIPTION", 1),
    ("硫酸氢氯吡格雷片", "波立维", "75mg×7片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起出血；出现异常出血请及时就医", "密封，在30℃以下保存", "赛诺菲制药有限公司", "国药准字J20180032", "PRESCRIPTION", 1),
    ("盐酸二甲双胍片", "格华止", "500mg×20片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起胃肠道不适；建议随餐或餐后服用", "密封，在30℃以下保存", "中美上海施贵宝制药有限公司", "国药准字H20023370", "PRESCRIPTION", 1),
    ("格列美脲片", "亚莫利", "2mg×15片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起低血糖；随身备糖块", "密封，在30℃以下保存", "赛诺菲制药有限公司", "国药准字H20010531", "PRESCRIPTION", 1),
    ("阿卡波糖片", "拜唐苹", "50mg×30片", "TABLET", "口服，随第一口主食整片吞服，具体用法请遵医嘱", "可能引起腹胀、排气增多", "密封，在25℃以下保存", "拜耳医药保健有限公司", "国药准字H19990205", "PRESCRIPTION", 1),
    ("磷酸西格列汀片", "捷诺维", "100mg×7片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起上呼吸道感染症状", "密封，在30℃以下保存", "默沙东制药有限公司", "国药准字J20140022", "PRESCRIPTION", 1),
    ("达格列净片", "安达唐", "10mg×14片", "TABLET", "口服，具体用法用量请遵医嘱", "注意泌尿系统感染风险，适量饮水", "密封，在30℃以下保存", "阿斯利康制药有限公司", "国药准字J20170040", "PRESCRIPTION", 1),
    ("精蛋白人胰岛素注射液", "诺和灵N", "300IU/3ml", "INJECTION", "皮下注射，具体用法用量请遵医嘱", "需冷藏保存；注意低血糖反应", "2-8℃冷藏，避免冷冻", "诺和诺德（中国）制药有限公司", "国药准字J20160015", "PRESCRIPTION", 1),
    ("左甲状腺素钠片", "优甲乐", "50μg×100片", "TABLET", "口服，早餐前半小时空腹服用，具体用法请遵医嘱", "避免与钙剂、铁剂同服", "密封，在25℃以下避光保存", "默克制药有限公司", "国药准字H20140052", "PRESCRIPTION", 1),
    ("甲巯咪唑片", "赛治", "10mg×50片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起白细胞减少，需定期复查血常规", "密封，在30℃以下保存", "默克制药有限公司", "国药准字H20120134", "PRESCRIPTION", 1),
    ("碳酸钙D3片", "钙尔奇D", "600mg×60片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起便秘；肾结石患者慎用", "密封，在干燥处保存", "惠氏制药有限公司", "国药准字H10950029", "OTC", 1),
    ("阿仑膦酸钠片", "福善美", "70mg×1片", "TABLET", "口服，晨起空腹用足量水送服，服后保持站立30分钟", "服后勿立即躺卧；食管疾病患者慎用", "密封，在30℃以下保存", "默沙东制药有限公司", "国药准字J20130085", "PRESCRIPTION", 1),
    ("骨化三醇软胶囊", "罗盖全", "0.25μg×10粒", "CAPSULE", "口服，具体用法用量请遵医嘱", "需定期监测血钙水平", "遮光，密封，在阴凉处保存", "上海罗氏制药有限公司", "国药准字H20010622", "PRESCRIPTION", 1),
    ("奥美拉唑肠溶胶囊", "洛赛克", "20mg×14粒", "CAPSULE", "口服，整粒吞服，不可咀嚼，具体用法请遵医嘱", "长期使用需监测维生素B12水平", "密封，在25℃以下保存", "阿斯利康制药有限公司", "国药准字H20033444", "PRESCRIPTION", 1),
    ("雷贝拉唑钠肠溶片", "波利特", "10mg×7片", "TABLET", "口服，整片吞服，具体用法请遵医嘱", "可能引起头痛、腹泻", "密封，在30℃以下保存", "卫材（中国）药业有限公司", "国药准字H20040612", "PRESCRIPTION", 1),
    ("铝碳酸镁咀嚼片", "达喜", "500mg×20片", "TABLET", "咀嚼后服用，具体用法请遵医嘱", "可能影响其他药物吸收，建议间隔1-2小时", "密封，在干燥处保存", "拜耳医药保健有限公司", "国药准字H20013410", "OTC", 1),
    ("多潘立酮片", "吗丁啉", "10mg×30片", "TABLET", "口服，饭前15-30分钟服用，具体用法请遵医嘱", "可能引起口干；心脏疾病患者慎用", "密封，在30℃以下保存", "西安杨森制药有限公司", "国药准字H10910003", "OTC", 1),
    ("枸橼酸莫沙必利片", "加斯清", "5mg×10片", "TABLET", "口服，饭前服用，具体用法请遵医嘱", "可能引起腹泻、腹痛", "密封，在30℃以下保存", "住友制药（苏州）有限公司", "国药准字J20140119", "PRESCRIPTION", 1),
    ("蒙脱石散", "思密达", "3g×10袋", "OTHER", "口服，倒入温水中搅匀后服用，具体用法请遵医嘱", "可能引起便秘；与其他药物间隔服用", "密封，在干燥处保存", "博福-益普生（天津）制药有限公司", "国药准字H20000690", "OTC", 1),
    ("双歧杆菌三联活菌胶囊", "培菲康", "210mg×36粒", "CAPSULE", "口服，饭后温水送服，具体用法请遵医嘱", "避免与抗菌药物同时服用；需冷藏", "2-8℃冷藏保存", "上海上药信谊药厂有限公司", "国药准字S10950032", "OTC", 1),
    ("乳果糖口服溶液", "杜密克", "15ml×6袋", "LIQUID", "口服，具体用法用量请遵医嘱", "可能引起腹胀；糖尿病患者注意含糖量", "密封，在30℃以下保存", "雅培制药有限公司", "国药准字H20070221", "OTC", 1),
    ("头孢克肟分散片", "世福素", "100mg×6片", "TABLET", "口服，具体用法用量请遵医嘱", "青霉素过敏者慎用；用药期间禁酒", "密封，在30℃以下保存", "广州白云山制药股份有限公司", "国药准字H20051230", "PRESCRIPTION", 1),
    ("阿莫西林胶囊", "阿莫仙", "0.25g×24粒", "CAPSULE", "口服，具体用法用量请遵医嘱", "青霉素过敏者禁用；用药期间禁酒", "密封，在30℃以下保存", "珠海联邦制药股份有限公司", "国药准字H44021518", "PRESCRIPTION", 1),
    ("左氧氟沙星片", "可乐必妥", "0.5g×5片", "TABLET", "口服，具体用法用量请遵医嘱", "18岁以下禁用；光敏体质者避免日晒", "密封，在30℃以下保存", "第一三共制药（北京）有限公司", "国药准字H20040091", "PRESCRIPTION", 1),
    ("阿奇霉素分散片", "希舒美", "0.25g×6片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起胃肠道不适", "密封，在干燥处保存", "辉瑞制药有限公司", "国药准字H10960167", "PRESCRIPTION", 1),
    ("甲硝唑片", "灭滴灵", "0.2g×100片", "TABLET", "口服，具体用法用量请遵医嘱", "用药期间及停药后3天内禁酒", "密封，避光保存", "华中药业股份有限公司", "国药准字H42020218", "PRESCRIPTION", 1),
    ("盐酸氨溴索口服溶液", "沐舒坦", "100ml", "LIQUID", "口服，具体用法用量请遵医嘱", "可能引起轻微胃肠道反应", "密封，在30℃以下保存", "上海勃林格殷格翰药业有限公司", "国药准字H20031314", "OTC", 1),
    ("氢溴酸右美沙芬缓释混悬液", "惠菲宁", "100ml", "LIQUID", "口服，具体用法用量请遵医嘱", "可能引起嗜睡；服药期间避免驾驶", "密封，在30℃以下保存", "惠氏制药有限公司", "国药准字H20060031", "OTC", 0),
    ("孟鲁司特钠片", "顺尔宁", "10mg×5片", "TABLET", "口服，具体用法用量请遵医嘱", "睡前服用；可能引起头痛", "密封，在30℃以下避光保存", "默沙东制药有限公司", "国药准字J20130047", "PRESCRIPTION", 1),
    ("布地奈德福莫特罗吸入粉雾剂", "信必可都保", "160μg/4.5μg×60吸", "OTHER", "经口吸入，具体用法用量请遵医嘱", "吸药后需漱口；不可突然停药", "密封，在30℃以下干燥处保存", "阿斯利康制药有限公司", "国药准字H20140458", "PRESCRIPTION", 1),
    ("硫酸沙丁胺醇气雾剂", "万托林", "100μg×200揿", "OTHER", "经口吸入，具体用法用量请遵医嘱", "可能引起心悸、手抖；避免过量使用", "密闭，在阴凉处保存，避免受热", "葛兰素史克（中国）投资有限公司", "国药准字H10940001", "PRESCRIPTION", 1),
    ("氯雷他定片", "开瑞坦", "10mg×6片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起口干、嗜睡", "密封，在30℃以下保存", "拜耳医药保健有限公司", "国药准字H10970410", "OTC", 1),
    ("盐酸西替利嗪片", "仙特明", "10mg×12片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起嗜睡；服药期间避免驾驶", "密封，在30℃以下保存", "优时比（珠海）制药有限公司", "国药准字H20050566", "OTC", 1),
    ("马来酸氯苯那敏片", "扑尔敏", "4mg×100片", "TABLET", "口服，具体用法用量请遵医嘱", "嗜睡作用明显；青光眼、前列腺肥大者慎用", "密封，避光保存", "华润双鹤药业股份有限公司", "国药准字H11021360", "OTC", 0),
    ("地塞米松片", "氟美松", "0.75mg×100片", "TABLET", "口服，具体用法用量请遵医嘱", "长期使用需逐渐减量，不可骤停", "密封，在干燥处保存", "天津天药药业股份有限公司", "国药准字H12020011", "PRESCRIPTION", 0),
    ("醋酸泼尼松片", "强的松", "5mg×100片", "TABLET", "口服，具体用法用量请遵医嘱", "长期使用需监测血糖、血压；不可骤停", "密封，在干燥处保存", "浙江仙琚制药股份有限公司", "国药准字H33021207", "PRESCRIPTION", 0),
    ("秋水仙碱片", "秋水仙碱片", "0.5mg×20片", "TABLET", "口服，具体用法用量请遵医嘱", "治疗窗窄，出现腹泻请立即停药并就医", "密封，避光保存", "昆明制药集团股份有限公司", "国药准字H53021369", "PRESCRIPTION", 0),
    ("非布司他片", "优立通", "40mg×14片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起肝功能异常；需定期复查", "密封，在30℃以下保存", "江苏万邦生化医药股份有限公司", "国药准字H20130058", "PRESCRIPTION", 0),
    ("别嘌醇片", "别嘌醇片", "0.1g×100片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起皮疹，严重者需立即就医", "密封，在干燥处保存", "上海信谊万象药业股份有限公司", "国药准字H31021467", "PRESCRIPTION", 0),
    ("塞来昔布胶囊", "西乐葆", "0.2g×6粒", "CAPSULE", "口服，具体用法用量请遵医嘱", "可能增加心血管风险；有磺胺过敏史者慎用", "密封，在30℃以下保存", "辉瑞制药有限公司", "国药准字J20140035", "PRESCRIPTION", 1),
    ("双氯芬酸钠缓释片", "扶他林", "75mg×10片", "TABLET", "口服，整片吞服，具体用法请遵医嘱", "可能引起胃部不适；建议餐后服用", "密封，在30℃以下保存", "北京诺华制药有限公司", "国药准字H10980297", "PRESCRIPTION", 1),
    ("硫酸氨基葡萄糖胶囊", "维骨力", "0.24g×60粒", "CAPSULE", "口服，具体用法用量请遵医嘱", "可能引起轻度胃肠道不适", "密封，在干燥处保存", "罗达药厂", "国药准字J20120046", "OTC", 0),
    ("甲钴胺片", "弥可保", "0.5mg×20片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起食欲不振、恶心", "密封，在30℃以下避光保存", "卫材（中国）药业有限公司", "国药准字H20030812", "PRESCRIPTION", 1),
    ("维生素B1片", "维生素B1片", "10mg×100片", "TABLET", "口服，具体用法用量请遵医嘱", "一般耐受良好", "密封，避光保存", "华中药业股份有限公司", "国药准字H42020261", "OTC", 1),
    ("维生素B12片", "维生素B12片", "25μg×100片", "TABLET", "口服，具体用法用量请遵医嘱", "一般耐受良好", "密封，避光保存", "华中药业股份有限公司", "国药准字H42020262", "OTC", 0),
    ("维生素C片", "维生素C片", "100mg×100片", "TABLET", "口服，具体用法用量请遵医嘱", "长期大剂量服用需遵医嘱", "密封，避光保存", "华北制药股份有限公司", "国药准字H13020637", "OTC", 1),
    ("复合维生素B片", "复合维生素B片", "100片", "TABLET", "口服，具体用法用量请遵医嘱", "服后尿液可能呈黄色，属正常现象", "密封，避光保存", "上海信谊药厂有限公司", "国药准字H31021379", "OTC", 1),
    ("叶酸片", "斯利安", "0.4mg×93片", "TABLET", "口服，具体用法用量请遵医嘱", "一般耐受良好", "密封，避光保存", "北京北大药业有限公司", "国药准字H10970051", "OTC", 0),
    ("琥珀酸亚铁片", "速力菲", "0.1g×20片", "TABLET", "口服，饭后服用可减轻胃肠刺激，具体用法请遵医嘱", "可能引起黑便，属正常现象", "密封，避光保存", "金陵药业股份有限公司", "国药准字H10930088", "PRESCRIPTION", 1),
    ("银杏叶片", "金纳多", "19.2mg×24片", "TABLET", "口服，具体用法用量请遵医嘱", "有出血倾向者慎用", "密封，在干燥处保存", "德国威玛舒培博士药厂", "国药准字J20140002", "PRESCRIPTION", 1),
    ("尼莫地平片", "尼膜同", "30mg×20片", "TABLET", "口服，具体用法用量请遵医嘱", "可能引起血压下降、头晕", "密封，避光保存", "拜耳医药保健有限公司", "国药准字H10910028", "PRESCRIPTION", 0),
    ("盐酸多奈哌齐片", "安理申", "5mg×7片", "TABLET", "口服，睡前服用，具体用法请遵医嘱", "可能引起胃肠道不适、失眠", "密封，在30℃以下保存", "卫材（中国）药业有限公司", "国药准字H20050978", "PRESCRIPTION", 1),
    ("吡非尼酮胶囊", "艾思瑞", "100mg×45粒", "CAPSULE", "口服，具体用法用量请遵医嘱", "需严格遵医嘱使用；注意光敏反应", "密封，在30℃以下避光保存", "北京康蒂尼药业有限公司", "国药准字H20160035", "PRESCRIPTION", 0),
]

MEAL = ["AFTER_MEAL", "BEFORE_MEAL", "ANY"]

lines = []          # 输出缓冲
def w(s=""):
    lines.append(s)

def q(s):
    """字符串字面量，单引号转义"""
    if s is None:
        return "NULL"
    return "'" + str(s).replace("\\", "\\\\").replace("'", "''") + "'"

def num(v):
    return "NULL" if v is None else str(v)

def dt(y, mo, d, h=0, mi=0, s=0):
    return "%04d-%02d-%02d %02d:%02d:%02d" % (y, mo, d, h, mi, s)

def dstr(y, mo, d):
    return "%04d-%02d-%02d" % (y, mo, d)

def jq(lst):
    """JSON 数组/对象的原始文本（不含 SQL 引号，由调用方用 q() 包裹）"""
    return json.dumps(lst, ensure_ascii=False, separators=(",", ":"))

DAY = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
def add_days(y, mo, d, delta):
    while delta > 0:
        d += 1
        if d > DAY[mo - 1]:
            d = 1
            mo += 1
            if mo > 12:
                mo = 1
                y += 1
        delta -= 1
    return y, mo, d

# ============================================================ 头部
w("-- =============================================================================")
w("-- 银龄伴诊 —— 种子数据（演示 / 答辩用）")
w("-- -----------------------------------------------------------------------------")
w("-- 版本      : V2")
w("-- 覆盖模块  : M1 数据库设计与数据初始化")
w("-- 生成方式  : 由脚本按固定随机种子生成，可复现；请勿手工零散修改")
w("--")
w("-- 规模：20 张表全部灌入，每张业务表行数 >= 30。")
w("-- 边界场景覆盖：")
w("--   1. 空字段        —— 部分老人无身份证/无手机号/无过敏史，部分订单无备注")
w("--   2. 超长文本      —— 病史备注、服务小结、投诉内容接近字段上限")
w("--   3. 非常用药      —— 药品字典含罕见病用药（吡非尼酮）且 is_common = 0")
w("--   4. 跨天用药      —— 用药计划含 00:30 / 22:00 等跨零点时间点")
w("--   5. 长期用药      —— end_date 为 NULL 表示长期服药")
w("--   6. 漏服补记      —— 服药任务含 MISSED 与 was_missed = 1（补记）两种")
w("--   7. 资质非通过态  —— 陪诊员含 PENDING / REJECTED / 健康证过期")
w("--")
w("-- 种子账号统一密码：Nl@123456（BCrypt 已入库，可直接登录）")
w("-- =============================================================================")
w()
w("SET NAMES utf8mb4;")
w()
w("-- 便于重复演示：先清空业务表，再重新灌入（顺序与外键无关，本库未建物理外键）")
w("-- 若只想增量追加，可注释掉下面这段 TRUNCATE。")
w("TRUNCATE TABLE `sys_user`;")
w("TRUNCATE TABLE `sys_login_log`;")
w("TRUNCATE TABLE `sys_dict`;")
w("TRUNCATE TABLE `sys_file`;")
w("TRUNCATE TABLE `admin_oper_log`;")
w("TRUNCATE TABLE `elder_profile`;")
w("TRUNCATE TABLE `family_elder_relation`;")
w("TRUNCATE TABLE `companion_audit_record`;")
w("TRUNCATE TABLE `companion_profile`;")
w("TRUNCATE TABLE `companion_order`;")
w("TRUNCATE TABLE `order_status_log`;")
w("TRUNCATE TABLE `order_reject_log`;")
w("TRUNCATE TABLE `order_checkin`;")
w("TRUNCATE TABLE `companion_track`;")
w("TRUNCATE TABLE `medicine_dict`;")
w("TRUNCATE TABLE `medication_plan`;")
w("TRUNCATE TABLE `medication_task`;")
w("TRUNCATE TABLE `order_review`;")
w("TRUNCATE TABLE `complaint`;")
w("TRUNCATE TABLE `internal_message`;")
w()

# ============================================================ 1. sys_user
w("-- =============================================================================")
w("-- 1. sys_user —— 用户主表（2 管理员 + 30 家属 + 30 老人 + 30 陪诊员 = 92 条）")
w("-- =============================================================================")
w("INSERT INTO `sys_user`")
w("  (`id`,`username`,`password`,`nickname`,`real_name`,`phone`,`avatar`,`role`,`status`,")
w("   `need_change_password`,`last_login_time`,`last_login_ip`,`remark`,`create_time`) VALUES")
rows = []
ADMIN_IDS = [1, 2]
rows.append((1, "admin", "系统管理员", "王建国", "13800000001", "ADMIN", "NORMAL", 0,
             dt(2026, 8, 1, 9, 0, 0), "192.168.3.10", None, dt(2026, 8, 1, 8, 30, 0)))
rows.append((2, "superadmin", "超级管理员", "刘志强", "13800000002", "ADMIN", "NORMAL", 0,
             dt(2026, 9, 14, 10, 30, 0), "192.168.3.11", None, dt(2026, 8, 1, 8, 31, 0)))

FAMILY_IDS = list(range(101, 131))
for i, fid in enumerate(FAMILY_IDS):
    u, mo, d = add_days(2026, 8, 20, i)
    rows.append((fid, "fam%03d" % (i + 1), "家属%02d" % (i + 1), FAMILY_NAMES[i],
                 "1380010%04d" % (i + 1), "FAMILY", "NORMAL", 0,
                 dt(2026, 9, 15, 8 + (i % 12), i % 60, 0), "192.168.3.%d" % (20 + i % 200),
                 None, dt(u, mo, d, 9 + i % 10, i % 60, 0)))

ELDER_IDS = list(range(201, 231))
for i, eid in enumerate(ELDER_IDS):
    u, mo, d = add_days(2026, 8, 20, i)
    nick = ELDER_NAMES[i][0] + ("大爷" if ELDER_GENDER[i] == "MALE" else "阿姨")
    rows.append((eid, "elder%03d" % (i + 1), nick, ELDER_NAMES[i],
                 "1390010%04d" % (i + 1), "ELDER",
                 "DISABLED" if i == 29 else "NORMAL", 0,
                 dt(2026, 9, 15, 7 + (i % 10), i % 60, 0), "192.168.3.%d" % (60 + i),
                 "演示用：该老人账号已被管理员封禁（越权用例）" if i == 29 else None,
                 dt(u, mo, d, 10 + i % 8, i % 60, 0)))

COMPANION_IDS = list(range(301, 331))
for i, cid in enumerate(COMPANION_IDS):
    u, mo, d = add_days(2026, 8, 18, i)
    rows.append((cid, "comp%03d" % (i + 1), "陪诊员%02d" % (i + 1), COMPANION_NAMES[i],
                 "1370010%04d" % (i + 1), "COMPANION", "NORMAL", 0,
                 dt(2026, 9, 15, 6 + (i % 14), i % 60, 0), "192.168.3.%d" % (100 + i),
                 None, dt(u, mo, d, 11 + i % 8, i % 60, 0)))

for idx, r in enumerate(rows):
    (rid, un, nick, real, ph, role, st, need, last_login, ip, remark, ct) = r
    w("  (%d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)%s" % (
        rid, q(un), q(BCRYPT), q(nick), q(real), q(ph), q("/uploads/avatar/default.png"),
        q(role), q(st), num(need), q(last_login), q(ip), q(remark), q(ct),
        "," if idx < len(rows) - 1 else ";"))
w()

# ============================================================ 2. sys_login_log
w("-- =============================================================================")
w("-- 2. sys_login_log —— 登录日志（40 条，含 8 条失败记录）")
w("-- =============================================================================")
w("INSERT INTO `sys_login_log`")
w("  (`id`,`user_id`,`username`,`login_type`,`status`,`fail_reason`,`ip`,`user_agent`,`login_time`) VALUES")
log_rows = []
lid = 43001
fail_reasons = ["账号或密码错误", "验证码错误或已过期", "账号已被封禁", "登录失败次数过多，已锁定"]
all_accounts = ([(i, "admin") for i in ADMIN_IDS]
                + [(i, "fam%03d" % (i - 100)) for i in FAMILY_IDS]
                + [(i, "elder%03d" % (i - 200)) for i in ELDER_IDS]
                + [(i, "comp%03d" % (i - 300)) for i in COMPANION_IDS])
for k in range(40):
    uid, un = all_accounts[k % len(all_accounts)]
    y, mo, d = add_days(2026, 9, 1, k)
    if k % 5 == 4:
        log_rows.append((lid, uid, un, "LOGIN", "FAIL",
                         fail_reasons[(k // 5) % len(fail_reasons)],
                         "192.168.3.%d" % (20 + k % 200),
                         "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0",
                         dt(y, mo, d, 8 + k % 12, k % 60, 0)))
    else:
        log_rows.append((lid, uid, un, "LOGIN" if k % 7 else "LOGOUT", "SUCCESS", None,
                         "192.168.3.%d" % (20 + k % 200),
                         "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0",
                         dt(y, mo, d, 8 + k % 12, k % 60, 0)))
    lid += 1
for idx, r in enumerate(log_rows):
    w("  (%d, %s, %s, %s, %s, %s, %s, %s, %s)%s" % (
        r[0], num(r[1]), q(r[2]), q(r[3]), q(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]),
        "," if idx < len(log_rows) - 1 else ";"))
w()

# ============================================================ 3. sys_dict
w("-- =============================================================================")
w("-- 3. sys_dict —— 数据字典（45 条：医院 10 / 科室 15 / 服务类型 3 /")
w("--                 投诉类型 6 / 结算状态 2 / 行动能力 3 / 订单状态 6）")
w("-- =============================================================================")
w("INSERT INTO `sys_dict` (`id`,`dict_type`,`dict_code`,`dict_label`,`dict_value`,`sort_no`,`status`) VALUES")
dict_rows = []
did = 44001
for i, (hn, ha) in enumerate(HOSPITALS):
    dict_rows.append((did, "HOSPITAL", "H%02d" % (i + 1), hn, ha, i + 1, 1)); did += 1
for i, dn in enumerate(DEPARTMENTS):
    dict_rows.append((did, "DEPARTMENT", "D%02d" % (i + 1), dn, "", i + 1, 1)); did += 1
for i, (c, l) in enumerate([("ESCORT", "全程陪诊"), ("HALF", "半程陪同"), ("GUIDE", "院内引导")]):
    dict_rows.append((did, "SERVICE_TYPE", c, l, "", i + 1, 1)); did += 1
for i, (c, l) in enumerate([("LATE", "迟到/未按时到达"), ("ATTITUDE", "服务态度差"),
                            ("INCOMPLETE", "服务未完成"), ("FEE_DISPUTE", "费用纠纷"),
                            ("PRIVACY", "隐私泄露"), ("OTHER", "其他")]):
    dict_rows.append((did, "COMPLAINT_TYPE", c, l, "", i + 1, 1)); did += 1
for i, (c, l) in enumerate([("UNPAID", "未结算"), ("SETTLED", "已结算")]):
    dict_rows.append((did, "PAYMENT_STATUS", c, l, "", i + 1, 1)); did += 1
for i, (c, l) in enumerate([("SELF", "生活可自理"), ("ASSIST", "需搀扶"), ("WHEELCHAIR", "需轮椅")]):
    dict_rows.append((did, "MOBILITY_LEVEL", c, l, "", i + 1, 1)); did += 1
for i, (c, l) in enumerate([("PENDING", "待接单"), ("ACCEPTED", "已接单"), ("IN_SERVICE", "服务中"),
                            ("COMPLETED", "已完成"), ("REVIEWED", "已评价"), ("CANCELLED", "已取消")]):
    dict_rows.append((did, "ORDER_STATUS", c, l, "", i + 1, 1)); did += 1
for idx, r in enumerate(dict_rows):
    w("  (%d, %s, %s, %s, %s, %d, %d)%s" % (r[0], q(r[1]), q(r[2]), q(r[3]), q(r[4]), r[5], r[6],
                                            "," if idx < len(dict_rows) - 1 else ";"))
w()

# ============================================================ 4. sys_file
w("-- =============================================================================")
w("-- 4. sys_file —— 附件登记（36 条：资质证件 12 / 打卡照片 12 / 投诉证据 6 / 评价图片 6）")
w("-- =============================================================================")
w("INSERT INTO `sys_file`")
w("  (`id`,`file_id`,`origin_name`,`stored_name`,`url`,`store_path`,`size`,`ext`,`content_type`,`biz_type`,`biz_id`,`uploader_id`,`create_time`) VALUES")
file_rows = []
fid = 70001
for i in range(12):
    f = "f_cert_%02d" % (i + 1)
    file_rows.append((fid, f, "身份证正面.jpg", "%s.jpg" % f, "/uploads/202609/cert%02d.jpg" % (i + 1),
                      "uploads/202609/cert%02d.jpg" % (i + 1), 204800 + i * 1024, "jpg",
                      "image/jpeg", "COMPANION_CERT", 701 + i, 301 + i, dt(2026, 9, 2, 10, i, 0)))
    fid += 1
for i in range(12):
    f = "f_ck_%02d" % (i + 1)
    file_rows.append((fid, f, "打卡现场.jpg", "%s.jpg" % f, "/uploads/202609/ck%02d.jpg" % (i + 1),
                      "uploads/202609/ck%02d.jpg" % (i + 1), 153600 + i * 2048, "jpg",
                      "image/jpeg", "CHECKIN", 4001 + i, 301 + i % 30, dt(2026, 9, 10, 9, i, 0)))
    fid += 1
for i in range(6):
    f = "f_cp_%02d" % (i + 1)
    file_rows.append((fid, f, "投诉证据.png", "%s.png" % f, "/uploads/202609/cp%02d.png" % (i + 1),
                      "uploads/202609/cp%02d.png" % (i + 1), 307200 + i * 4096, "png",
                      "image/png", "COMPLAINT", 31001 + i, 101 + i, dt(2026, 9, 12, 15, i, 0)))
    fid += 1
for i in range(6):
    f = "f_rv_%02d" % (i + 1)
    file_rows.append((fid, f, "评价晒图.webp", "%s.webp" % f, "/uploads/202609/rv%02d.webp" % (i + 1),
                      "uploads/202609/rv%02d.webp" % (i + 1), 102400 + i * 1024, "webp",
                      "image/webp", "REVIEW", 30001 + i, 101 + i, dt(2026, 9, 13, 20, i, 0)))
    fid += 1
for idx, r in enumerate(file_rows):
    w("  (%d, %s, %s, %s, %s, %s, %d, %s, %s, %s, %d, %d, %s)%s" % (
        r[0], q(r[1]), q(r[2]), q(r[3]), q(r[4]), q(r[5]), r[6], q(r[7]), q(r[8]),
        q(r[9]), r[10], r[11], q(r[12]), "," if idx < len(file_rows) - 1 else ";"))
w()

# ============================================================ 5. elder_profile
w("-- =============================================================================")
w("-- 5. elder_profile —— 老人档案（32 条：30 条已绑定 + 2 条 UNBOUND 未绑定）")
w("--    身份证号已 AES-256-GCM 加密；后 20 条留空，覆盖「档案信息不全」场景")
w("-- =============================================================================")
w("INSERT INTO `elder_profile`")
w("  (`id`,`user_id`,`name`,`gender`,`birth_date`,`id_card`,`phone`,`address`,")
w("   `emergency_contact`,`emergency_phone`,`medical_history`,`allergy_history`,`mobility_level`,")
w("   `favorite_hospital`,`bind_status`,`create_by`,`remark`,`create_time`) VALUES")
ELDER_PROFILE_IDS = list(range(401, 433))
mobility = ["SELF", "ASSIST", "WHEELCHAIR", "SELF", "ASSIST"]
ep_rows = []
for i in range(32):
    pid = 401 + i
    bound = i < 30                      # 后 2 条为「未绑定」档案，覆盖 bindStatus=UNBOUND 筛选
    uid = 201 + i if bound else None
    nm = ELDER_NAMES[i % 30]
    gd = ELDER_GENDER[i % 30]
    y, mo, d = 1938 + i % 19, 1 + i % 12, 1 + i % 27
    idc = ID_CARDS[i % 12] if i < 12 else None
    ph = "1390010%04d" % (i + 1) if bound else None      # 未绑定档案留空手机号，覆盖空字段边界
    addr = "%s%s小区%d号楼%d单元%02d%02d室" % (AREAS[i % len(AREAS)],
             ["春晖", "康乐", "和安", "福寿", "长青"][i % 5], 1 + i % 20, 1 + i % 4, 1 + i % 6, 1 + i % 8)
    ec = ELDER_NAMES[(i + 1) % 30]
    ep = "1380010%04d" % ((i % 30) + 1)
    mh = ["高血压、2型糖尿病，长期服药", "冠心病，2023年做过支架植入术",
          "高血压、慢性胃炎", "骨质疏松，偶有腰背疼痛", "2型糖尿病，血糖控制尚可",
          "高血压十余年，血压波动较大", None, "慢性支气管炎，冬季易咳嗽",
          "高血压、高脂血症", "骨质疏松、轻度贫血"][i % 10]
    if i == 3:
        mh = ("高血压 25 年，最高 180/100mmHg，目前服用缬沙坦 80mg 每日一次、苯磺酸氨氯地平 5mg 每日一次；"
              "2 型糖尿病 12 年，长期口服二甲双胍 500mg 每日三次，餐后血糖波动在 8-12mmol/L；"
              "2022 年因冠心病行冠脉支架植入术（前降支 1 枚），术后规律服用阿司匹林 100mg 与氯吡格雷 75mg；"
              "合并慢性肾功能不全（CKD 3 期），血肌酐 156μmol/L，eGFR 42ml/min，需限制高蛋白与高钾饮食；"
              "既往 2019 年有脑梗史，遗留轻度右侧肢体活动不便，行走需搀扶，右手握力较差；"
              "另有慢性胃炎与骨质疏松，长期服用奥美拉唑与碳酸钙 D3；对青霉素过敏；"
              "用药清单：缬沙坦、氨氯地平、二甲双胍、阿司匹林、氯吡格雷、阿托伐他汀、美托洛尔缓释片、碳酸钙 D3；"
              "需定期复查肝功能、肾功能、血脂四项、糖化血红蛋白与心电图，建议每 3 个月复诊心内科与肾内科各一次，"
              "复诊时携带全部药盒与既往检查报告，便于医生核对用药方案与调整剂量。")
    al = ["青霉素过敏", "无已知过敏史", None, "磺胺类药物过敏"][i % 4]
    ml = mobility[i % 5]
    hd = HOSPITALS[i % len(HOSPITALS)][0]
    bs = "BOUND" if bound else "UNBOUND"
    cb = FAMILY_IDS[i] if bound else None
    rm = None if i % 7 else "家属补充：老人听力一般，沟通需放慢语速"
    if not bound:
        rm = "由平台客服代录，家属尚未完成绑定"
    ep_rows.append((pid, uid, nm, gd, dstr(y, mo, d), idc, ph, addr,
                    ec, ep, mh, al, ml, hd, bs, cb, rm, dt(2026, 8, 20 + i % 10, 9 + i % 10, i % 60, 0)))
for idx, r in enumerate(ep_rows):
    w("  (%d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)%s" % (
        r[0], num(r[1]), q(r[2]), q(r[3]), q(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]),
        q(r[9]), q(r[10]), q(r[11]), q(r[12]), q(r[13]), q(r[14]), num(r[15]), q(r[16]), q(r[17]),
        "," if idx < len(ep_rows) - 1 else ";"))
w()

# ============================================================ 6. family_elder_relation
w("-- =============================================================================")
w("-- 6. family_elder_relation —— 家属绑定关系（30 条，均为 BOUND）")
w("-- =============================================================================")
w("INSERT INTO `family_elder_relation`")
w("  (`id`,`family_id`,`elder_id`,`relation`,`bind_type`,`is_default`,`status`,`bind_time`) VALUES")
fr_rows = []
for i in range(30):
    y, mo, d = add_days(2026, 8, 21, i)
    fr_rows.append((501 + i, FAMILY_IDS[i], 401 + i, FAMILY_RELATIONS[i],
                    "PHONE" if i % 3 else "INVITE_CODE", 1 if i % 2 == 0 else 0, "BOUND",
                    dt(y, mo, d, 10 + i % 8, i % 60, 0)))
for idx, r in enumerate(fr_rows):
    w("  (%d, %d, %d, %s, %s, %d, %s, %s)%s" % (
        r[0], r[1], r[2], q(r[3]), q(r[4]), r[5], q(r[6]), q(r[7]),
        "," if idx < len(fr_rows) - 1 else ";"))
w()

# ============================================================ 7. companion_audit_record
w("-- =============================================================================")
w("-- 7. companion_audit_record —— 资质申请记录（34 条，含 2 条二次提交的历史记录）")
w("-- =============================================================================")
w("INSERT INTO `companion_audit_record`")
w("  (`id`,`applicant_user_id`,`real_name`,`id_card`,`service_area`,`available_time`,")
w("   `certificates`,`apply_remark`,`audit_status`,`reject_reason`,`audit_admin_id`,")
w("   `audit_remark`,`submit_time`,`audit_time`) VALUES")
ca_rows = []
aid = 701
for i in range(30):
    cid = COMPANION_IDS[i]
    if i < 24:
        st, rr = "APPROVED", None
    elif i < 28:
        st, rr = "PENDING", None
    else:
        st, rr = "REJECTED", "身份证照片不清晰，请重新上传"
    y, mo, d = add_days(2026, 9, 1, i % 12)
    sub = dt(y, mo, d, 9 + i % 8, i % 60, 0)
    at = dt(y, mo, d, 14 + i % 4, 30 + i % 29, 0) if st != "PENDING" else None
    ca_rows.append((aid, cid, COMPANION_NAMES[i],
                    ID_CARDS[i % 12] if i < 12 else None,
                    AREAS[i % len(AREAS)],
                    ["周一至周五 08:00-18:00", "全周 07:00-20:00", "周末 09:00-17:00",
                     "周一至周六 08:30-17:30"][i % 4],
                    jq([{"name": "身份证正面", "url": "/uploads/202609/cert%02d.jpg" % (i % 12 + 1)}]),
                    None if i % 3 else "已从事陪护工作 5 年，熟悉省医院就诊流程",
                    st, rr, 1 if st != "PENDING" else None,
                    None if st != "REJECTED" else "第二次提交仍模糊，建议核对后再传",
                    sub, at))
    aid += 1
# 两条二次提交历史（同一申请人，先驳回后通过）
ca_rows.append((aid, COMPANION_IDS[28], COMPANION_NAMES[28], None, AREAS[2],
                "周一至周五 08:00-18:00",
                jq([{"name": "身份证正面", "url": "/uploads/202609/cert11.jpg"}]),
                "第一次上传的照片反光", "REJECTED", "照片反光无法识别，请重新上传", 2,
                "已电话告知申请人", dt(2026, 9, 3, 10, 20, 0), dt(2026, 9, 4, 9, 15, 0)))
aid += 1
ca_rows.append((aid, COMPANION_IDS[28], COMPANION_NAMES[28], None, AREAS[2],
                "周一至周五 08:00-18:00",
                jq([{"name": "身份证正面", "url": "/uploads/202609/cert11b.jpg"},
                    {"name": "健康证", "url": "/uploads/202609/health11.jpg"}]),
                "已按要求重新上传", "APPROVED", None, 2, "材料齐全，予以通过",
                dt(2026, 9, 6, 11, 5, 0), dt(2026, 9, 7, 9, 40, 0)))
aid += 1
# 两条额外申请（长时间未处理，用于 admin 待办演示）
ca_rows.append((aid, COMPANION_IDS[29], COMPANION_NAMES[29], None, AREAS[1],
                "全周 07:00-20:00",
                jq([{"name": "身份证正面", "url": "/uploads/202609/cert12.jpg"}]),
                None, "PENDING", None, None, None, dt(2026, 9, 12, 16, 40, 0), None))
aid += 1
ca_rows.append((aid, FAMILY_IDS[5], FAMILY_NAMES[5], None, AREAS[3],
                "周末 09:00-17:00",
                jq([{"name": "身份证正面", "url": "/uploads/202609/cert01.jpg"}]),
                "退休前是护士，想兼职做陪诊", "PENDING", None, None, None,
                dt(2026, 9, 14, 8, 25, 0), None))
for idx, r in enumerate(ca_rows):
    certs = "NULL" if r[6] is None else q(r[6])
    w("  (%d, %d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)%s" % (
        r[0], r[1], q(r[2]), q(r[3]), q(r[4]), q(r[5]), certs, q(r[7]),
        q(r[8]), q(r[9]), num(r[10]), q(r[11]), q(r[12]), q(r[13]),
        "," if idx < len(ca_rows) - 1 else ";"))
w()

# ============================================================ 8. companion_profile
w("-- =============================================================================")
w("-- 8. companion_profile —— 陪诊员资料（30 条：24 APPROVED / 4 PENDING / 2 REJECTED）")
w("--    含 2 名健康证已过期、4 名休息中，覆盖「不可接单」场景")
w("-- =============================================================================")
w("INSERT INTO `companion_profile`")
w("  (`id`,`user_id`,`real_name`,`id_card`,`gender`,`birth_date`,`phone`,`service_area`,")
w("   `available_time`,`introduction`,`certificate_no`,`health_cert_expire`,`audit_status`,")
w("   `reject_reason`,`audit_admin_id`,`audit_time`,`work_status`,`score`,`review_count`,")
w("   `order_count`,`accept_count`,`create_time`) VALUES")
cp_rows = []
for i in range(30):
    cid = COMPANION_IDS[i]
    if i < 24:
        st, rr, at = "APPROVED", None, dt(2026, 9, 2 + i % 10, 15, i % 60, 0)
    elif i < 28:
        st, rr, at = "PENDING", None, None
    else:
        ay, amo, ad = add_days(2026, 9, 5, i - 28)
        st, rr, at = "REJECTED", "身份证照片不清晰，请重新上传", dt(ay, amo, ad, 15, 0, 0)
    health = dstr(2027, 3 + i % 9, 10 + i % 18)
    if i in (5, 11):                       # 健康证过期
        health = dstr(2026, 6, 30)
    ws = "REST" if i % 7 == 3 and st == "APPROVED" else "AVAILABLE"
    score = round(4.0 + (i % 11) * 0.09, 2) if st == "APPROVED" else 0.00
    rc = (20 + i * 3) % 60 if st == "APPROVED" else 0
    oc = (18 + i * 3) % 55 if st == "APPROVED" else 0
    ac = oc + (i % 4) if st == "APPROVED" else 0
    intro = ["从事陪护工作 5 年，熟悉海口各大医院就诊流程，擅长陪同老年患者就医。",
             "护理专业毕业，曾在医院从事护理工作 3 年，沟通耐心细致。",
             "本地人，熟悉海南省人民医院、海口市人民医院各科室分布与检查流程。",
             "擅长与听力不佳、行动不便的老人沟通，可全程搀扶陪同。"][i % 4]
    cp_rows.append((601 + i, cid, COMPANION_NAMES[i], ID_CARDS[i % 12] if i < 12 else None,
                    "MALE" if i % 2 == 0 else "FEMALE",
                    dstr(1980 + i % 20, 1 + i % 12, 1 + i % 27),
                    "1370010%04d" % (i + 1), AREAS[i % len(AREAS)],
                    ["周一至周五 08:00-18:00", "全周 07:00-20:00", "周末 09:00-17:00",
                     "周一至周六 08:30-17:30"][i % 4],
                    intro, "HL%05d" % (10001 + i), health, st, rr,
                    1 if st != "PENDING" else None, at, ws,
                    "%.2f" % score, rc, oc, ac, dt(2026, 9, 1 + i % 6, 10, i % 60, 0)))
for idx, r in enumerate(cp_rows):
    w("  (%d, %d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %d, %d, %d, %s)%s" % (
        r[0], r[1], q(r[2]), q(r[3]), q(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]), q(r[9]),
        q(r[10]), q(r[11]), q(r[12]), q(r[13]), num(r[14]), q(r[15]), q(r[16]), q(r[17]),
        r[18], r[19], r[20], q(r[21]), "," if idx < len(cp_rows) - 1 else ";"))
w()

# ============================================================ 9. companion_order
w("-- =============================================================================")
w("-- 9. companion_order —— 陪诊订单（60 条：待接单 6 / 已接单 8 / 服务中 4 /")
w("--                        已完成 12 / 已评价 30 / 已取消 4 —— 六种状态全覆盖）")
w("-- =============================================================================")
# 状态分配
statuses = (["PENDING"] * 6 + ["ACCEPTED"] * 8 + ["IN_SERVICE"] * 4
            + ["COMPLETED"] * 12 + ["REVIEWED"] * 30 + ["CANCELLED"] * 4)
ORDER_IDS = list(range(1001, 1061))
order_meta = {}     # order_id -> dict，供后续表使用
w("INSERT INTO `companion_order`")
w("  (`id`,`order_no`,`family_id`,`elder_id`,`companion_id`,`hospital`,`department`,`visit_time`,")
w("   `address`,`longitude`,`latitude`,`remark`,`status`,`fee`,`actual_fee`,`payment_status`,")
w("   `service_summary`,`service_photos`,`accept_time`,`start_time`,`finish_time`,`cancel_time`,")
w("   `cancel_reason`,`cancel_by`,`arbitrate_flag`,`arbitrate_result`,`version`,`create_time`) VALUES")
order_rows = []
for i, oid in enumerate(ORDER_IDS):
    st = statuses[i]
    fam_i = i % 30
    family_id = FAMILY_IDS[fam_i]
    elder_id = 401 + fam_i
    hidx = i % len(HOSPITALS)
    hospital, haddr = HOSPITALS[hidx]
    dept = DEPARTMENTS[i % len(DEPARTMENTS)]
    # 下单时间：2026-08-18 起逐日铺开
    cy, cmo, cd = add_days(2026, 8, 18, i % 28)
    create_time = dt(cy, cmo, cd, 8 + i % 12, i % 60, 0)
    # 就诊时间：下单后 3-9 天
    vy, vmo, vd = add_days(cy, cmo, cd, 3 + i % 7)
    visit_time = dt(vy, vmo, vd, 8 + i % 8, (i * 7) % 60, 0)
    fee = "%d.00" % (98 + (i % 6) * 30)
    companion_id = None
    accept_time = start_time = finish_time = cancel_time = None
    cancel_reason = cancel_by = None
    service_summary = service_photos = actual_fee = None
    pay = "UNPAID"
    ver = 0
    if st != "PENDING":
        companion_id = COMPANION_IDS[i % 24]      # 只从已通过审核的里挑
        accept_time = dt(cy, cmo, cd, 8 + i % 12, (i + 17) % 60, 11)
        ver = 1
    if st in ("IN_SERVICE", "COMPLETED", "REVIEWED"):
        sy, smo, sd = add_days(vy, vmo, vd, 0)
        start_time = dt(sy, smo, sd, 8, 50 + i % 9, 0)
        ver = 2
    if st in ("COMPLETED", "REVIEWED"):
        fy, fmo, fd = add_days(vy, vmo, vd, 0)
        finish_time = dt(fy, fmo, fd, 11 + i % 5, 5 + i % 50, 0)
        ver = 3
        pay = "SETTLED" if i % 3 == 0 else "UNPAID"
        if i % 3 == 0:
            actual_fee = fee
        service_summary = ["已陪同老人完成挂号、就诊、缴费、取药，全程约 3 小时，老人状态良好。",
                           "陪同完成心内科复查，医生开具的检查单已协助完成，取药后交到家属手中。",
                           "老人行动不便，协助借用轮椅并全程搀扶，就诊流程顺利完成。"][i % 3]
        service_photos = jq(["/uploads/202609/ck%02d.jpg" % (i % 12 + 1)])
    if st == "CANCELLED":
        cancel_time = dt(cy, cmo, cd, 20, i % 60, 0)
        cancel_reason = ["家属临时有事，改期再约", "老人身体不适，暂缓就诊"][i % 2]
        cancel_by = family_id
    remark = [None, "老人听力不好，请大声沟通；需协助取药",
              "老人行动不便，需要轮椅，请提前 30 分钟到小区门口接",
              "需要帮忙排队缴费和取药，谢谢"][i % 4]
    if i == 5:
        remark = ("老人 82 岁，高血压合并 2 型糖尿病，行动不便需全程搀扶，请提前 30 分钟到小区门口接人；"
                  "听力下降明显，请贴近左耳放慢语速大声说话，必要时用纸笔沟通；"
                  "需要协助完成挂号、陪同进入诊室、记录医生口头交代的复诊时间与注意事项、排队缴费、到药房取药；"
                  "取药后请逐盒核对药品名称、规格与数量，拍照发给家属确认；"
                  "老人容易紧张焦虑，就诊等候期间请多安抚情绪，不要催促；"
                  "全程不要离开老人视线，如需离开请先告知家属；"
                  "结束后务必送到小区楼下，与家属或邻居当面交接，并电话通知家属已安全送达；"
                  "如就诊过程中医生要求加做检查，请第一时间联系家属确认后再执行。")
    order_rows.append((oid, "NL%s%06d" % ("%04d%02d%02d" % (cy, cmo, cd), i + 1), family_id,
                       elder_id, companion_id, hospital, dept, visit_time, haddr,
                       "110.%06d" % (311200 + i * 37), "20.%06d" % (21500 + i * 29),
                       remark, st, fee, actual_fee, pay, service_summary, service_photos,
                       accept_time, start_time, finish_time, cancel_time, cancel_reason,
                       cancel_by, 1 if i == 58 else 0,
                       None if i != 58 else "经核实陪诊员迟到 40 分钟且未提前告知，本次订单取消，服务费不结算。",
                       ver, create_time))
    order_meta[oid] = {"st": st, "family": family_id, "elder": elder_id,
                       "companion": companion_id, "order_no": order_rows[-1][1],
                       "create": create_time, "visit": visit_time, "fee": fee,
                       "accept": accept_time, "start": start_time,
                       "finish": finish_time, "cancel": cancel_time}
for idx, r in enumerate(order_rows):
    w("  (%d, %s, %d, %d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %d, %s, %d, %s)%s" % (
        r[0], q(r[1]), r[2], r[3], num(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]),
        q(r[9]), q(r[10]), q(r[11]), q(r[12]), q(r[13]), q(r[14]), q(r[15]), q(r[16]),
        "NULL" if r[17] is None else q(r[17]), q(r[18]), q(r[19]), q(r[20]), q(r[21]),
        q(r[22]), num(r[23]), r[24], q(r[25]), r[26], q(r[27]),
        "," if idx < len(order_rows) - 1 else ";"))
w()

# ============================================================ 10. order_status_log
w("-- =============================================================================")
w("-- 10. order_status_log —— 订单状态流转日志（按状态链路生成，保证与订单状态自洽）")
w("-- =============================================================================")
w("INSERT INTO `order_status_log`")
w("  (`id`,`order_id`,`from_status`,`to_status`,`operator_id`,`operator_name`,`operator_role`,`remark`,`is_force`,`operate_time`) VALUES")
sl_rows = []
sid = 2001
for oid in ORDER_IDS:
    m = order_meta[oid]
    # 下单
    sl_rows.append((sid, oid, None, "PENDING", m["family"],
                    FAMILY_NAMES[(m["family"] - 101)], "FAMILY", "下单成功", 0, m["create"]))
    sid += 1
    if m["st"] in ("ACCEPTED", "IN_SERVICE", "COMPLETED", "REVIEWED"):
        sl_rows.append((sid, oid, "PENDING", "ACCEPTED", m["companion"],
                        COMPANION_NAMES[(m["companion"] - 301)], "COMPANION", "已接单", 0, m["accept"]))
        sid += 1
    if m["st"] in ("IN_SERVICE", "COMPLETED", "REVIEWED"):
        sl_rows.append((sid, oid, "ACCEPTED", "IN_SERVICE", m["companion"],
                        COMPANION_NAMES[(m["companion"] - 301)], "COMPANION",
                        "已到达医院，开始陪诊", 0, m["start"]))
        sid += 1
    if m["st"] in ("COMPLETED", "REVIEWED"):
        sl_rows.append((sid, oid, "IN_SERVICE", "COMPLETED", m["companion"],
                        COMPANION_NAMES[(m["companion"] - 301)], "COMPANION",
                        "服务完成，已与家属交接", 0, m["finish"]))
        sid += 1
    if m["st"] == "REVIEWED":
        sl_rows.append((sid, oid, "COMPLETED", "REVIEWED", m["family"],
                        FAMILY_NAMES[(m["family"] - 101)], "FAMILY", "家属提交评价", 0,
                        dt(2026, 9, 14, 20, oid % 60, 0)))
        sid += 1
    if m["st"] == "CANCELLED":
        sl_rows.append((sid, oid, "PENDING", "CANCELLED", m["family"],
                        FAMILY_NAMES[(m["family"] - 101)], "FAMILY",
                        "家属主动取消，原因：临时有事改期", 0, m["cancel"]))
        sid += 1
for idx, r in enumerate(sl_rows):
    w("  (%d, %d, %s, %s, %s, %s, %s, %s, %d, %s)%s" % (
        r[0], r[1], q(r[2]), q(r[3]), num(r[4]), q(r[5]), q(r[6]), q(r[7]), r[8], q(r[9]),
        "," if idx < len(sl_rows) - 1 else ";"))
w()

# ============================================================ 11. order_reject_log
w("-- =============================================================================")
w("-- 11. order_reject_log —— 陪诊员拒单记录（36 条；不改变订单状态）")
w("-- =============================================================================")
w("INSERT INTO `order_reject_log` (`id`,`order_id`,`companion_id`,`reason`,`reject_time`) VALUES")
rj_rows = []
rid = 3001
pending_orders = [oid for oid in ORDER_IDS if order_meta[oid]["st"] == "PENDING"]
k = 0
while len(rj_rows) < 36:
    oid = pending_orders[k % len(pending_orders)]
    # 同一订单下换不同陪诊员，(order_id, companion_id) 才能保持唯一
    cid = COMPANION_IDS[(k // len(pending_orders)) % 30]
    m = order_meta[oid]
    y, mo, d = add_days(2026, 8, 20, (oid + k) % 26)
    rj_rows.append((rid, oid, cid,
                    ["当天已有其他订单，时间冲突", "距离太远，来回不方便",
                     "就诊科室不熟悉，怕耽误老人"][k % 3],
                    dt(y, mo, d, 9 + k % 10, k % 60, 0)))
    rid += 1
    k += 1
for idx, r in enumerate(rj_rows):
    w("  (%d, %d, %d, %s, %s)%s" % (r[0], r[1], r[2], q(r[3]), q(r[4]),
                                    "," if idx < len(rj_rows) - 1 else ";"))
w()

# ============================================================ 12. order_checkin
w("-- =============================================================================")
w("-- 12. order_checkin —— 打卡记录（已接单及之后的订单按节点链路打卡）")
w("-- =============================================================================")
CHECKIN_NODES = [("DEPART", 1), ("ARRIVE", 2), ("IN_CONSULT", 3),
                 ("TAKE_MEDICINE", 4), ("LEAVE", 5), ("FINISH", 6)]
w("INSERT INTO `order_checkin`")
w("  (`id`,`order_id`,`companion_id`,`node`,`node_sort`,`longitude`,`latitude`,`address`,")
w("   `distance`,`is_abnormal`,`photos`,`remark`,`checkin_time`) VALUES")
ck_rows = []
kid = 4001
for oid in ORDER_IDS:
    m = order_meta[oid]
    if m["st"] == "PENDING":
        continue
    if m["st"] == "ACCEPTED":
        nodes = CHECKIN_NODES[:1]                     # 只打了「出发」
    elif m["st"] == "IN_SERVICE":
        nodes = CHECKIN_NODES[:3]                     # 出发/到院/就诊中
    else:
        nodes = CHECKIN_NODES                         # 全链路
    by, bmo, bd = add_days(2026, 9, 1, oid % 14)
    for k, (node, sort) in enumerate(nodes):
        hh = 8 + k
        abnormal = 1 if (oid + k) % 23 == 0 else 0
        dist = 120 + (oid + k * 31) % 1800
        if abnormal:
            dist = 2600 + (oid % 400)
        ck = dt(by, bmo, bd, hh, (oid * 3 + k * 11) % 60, 0)
        ck_rows.append((kid, oid, m["companion"], node, sort,
                        "110.%06d" % (311200 + (oid % 60) * 37 + k * 5),
                        "20.%06d" % (21500 + (oid % 60) * 29 + k * 4),
                        ["海口市美兰区某小区门口", "海南省人民医院 门诊大楼",
                         "海南省人民医院 心血管内科候诊区", "海南省人民医院 门诊药房",
                         "海南省人民医院 门诊大楼出口", "海口市美兰区某小区门口"][k],
                        dist, abnormal,
                        jq(["/uploads/202609/ck%02d.jpg" % ((oid + k) % 12 + 1)]) if k % 3 == 0 else None,
                        ["已出发，预计 40 分钟到达", "已到达医院，正在取号",
                         "已陪同老人进入诊室", "正在排队缴费取药", "已离开医院，送老人回家",
                         "已送达并交接完成"][k], ck))
        kid += 1
for idx, r in enumerate(ck_rows):
    w("  (%d, %d, %d, %s, %d, %s, %s, %s, %d, %d, %s, %s, %s)%s" % (
        r[0], r[1], r[2], q(r[3]), r[4], q(r[5]), q(r[6]), q(r[7]), r[8], r[9],
        "NULL" if r[10] is None else q(r[10]), q(r[11]), q(r[12]),
        "," if idx < len(ck_rows) - 1 else ";"))
w()

# ============================================================ 13. companion_track
w("-- =============================================================================")
w("-- 13. companion_track —— 陪诊轨迹点（一期不渲染地图，用于文字摘要与申诉取证）")
w("-- =============================================================================")
w("INSERT INTO `companion_track`")
w("  (`id`,`order_id`,`companion_id`,`node`,`longitude`,`latitude`,`accuracy`,`speed`,`record_time`) VALUES")
tk_rows = []
tid = 5001
for r in ck_rows:
    tk_rows.append((tid, r[1], r[2], r[3], r[5], r[6],
                    "%.2f" % (5.0 + (tid % 17)), "%.2f" % ((tid % 13) * 1.4), r[12]))
    tid += 1
    # 每个节点前后各补一个纯轨迹点，模拟定位持续上报
    tk_rows.append((tid, r[1], r[2], None,
                    "110.%06d" % (int(r[5].split(".")[1]) - 12),
                    "20.%06d" % (int(r[6].split(".")[1]) - 9),
                    "%.2f" % (8.0 + (tid % 23)), "%.2f" % ((tid % 9) * 2.1), r[12]))
    tid += 1
for idx, r in enumerate(tk_rows):
    w("  (%d, %d, %d, %s, %s, %s, %s, %s, %s)%s" % (
        r[0], r[1], r[2], q(r[3]), q(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]),
        "," if idx < len(tk_rows) - 1 else ";"))
w()

# ============================================================ 14. medicine_dict
w("-- =============================================================================")
w("-- 14. medicine_dict —— 药品字典（%d 条）" % len(MEDICINES))
w("--     ⚠️ 只含通用信息与免责声明，不含剂量建议 / 适应症判断 / 替代药推荐")
w("-- =============================================================================")
w("INSERT INTO `medicine_dict`")
w("  (`id`,`name`,`trade_name`,`specification`,`dosage_form`,`common_usage`,`precautions`,")
w("   `storage`,`manufacturer`,`approval_no`,`otc_type`,`is_common`,`disclaimer`) VALUES")
md_rows = []
for i, m in enumerate(MEDICINES):
    prec = m[5]
    if i == 0:
        prec = ("本品为处方药，须在医师指导下使用；用药期间可能出现头晕、头痛、面部潮红、心悸、"
                "踝部及下肢水肿、牙龈增生等不良反应，通常为轻度至中度且随用药时间延长逐渐减轻；"
                "罕见情况下可能出现严重低血压、肝功能异常，如出现明显不适应立即停药并就医；"
                "肝功能不全者、严重主动脉瓣狭窄患者应谨慎使用并遵医嘱调整；"
                "请勿自行增减剂量或突然停药，长期用药需定期监测血压、心率、肝功能与血常规")
    md_rows.append((801 + i, m[0], m[1], m[2], m[3], m[4], prec, m[6], m[7], m[8],
                    m[9], m[10], DISCLAIMER))
for idx, r in enumerate(md_rows):
    w("  (%d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %d, %s)%s" % (
        r[0], q(r[1]), q(r[2]), q(r[3]), q(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]),
        q(r[9]), q(r[10]), r[11], q(r[12]), "," if idx < len(md_rows) - 1 else ";"))
w()

# ============================================================ 15. medication_plan
w("-- =============================================================================")
w("-- 15. medication_plan —— 用药计划（36 条：30 条 ACTIVE + 6 条 DISABLED）")
w("--     覆盖长期用药（end_date IS NULL）、跨天时间点、超长备注")
w("-- =============================================================================")
TIME_SETS = [
    (1, ["08:00"]), (2, ["08:00", "20:00"]), (3, ["08:00", "12:00", "18:00"]),
    (2, ["06:00", "22:00"]),                 # 跨天：早六点与晚十点
    (3, ["00:30", "08:30", "16:30"]),        # 跨零点服药
    (2, ["07:30", "19:30"]),
]
w("INSERT INTO `medication_plan`")
w("  (`id`,`elder_id`,`medicine_id`,`medicine_name`,`dosage`,`frequency`,`time_points`,")
w("   `start_date`,`end_date`,`meal_relation`,`status`,`remark`,`created_by`,`create_time`) VALUES")
mp_rows = []
plan_tps = {}          # plan_id -> 时间点列表，供服药任务展开使用
for i in range(36):
    pid = 10001 + i
    elder_id = 401 + (i % 30)
    fam_i = i % 30
    created_by = FAMILY_IDS[fam_i]
    mi = (i * 7) % 60
    med = MEDICINES[mi]
    freq, tps = TIME_SETS[i % len(TIME_SETS)]
    sy, smo, sd = add_days(2026, 8, 20, i % 20)
    start_date = dstr(sy, smo, sd)
    # 长期用药：偶数条 end_date 为 NULL
    if i % 2 == 0:
        end_date = None
    else:
        ey, emo, ed = add_days(sy, smo, sd, 30 + i % 60)
        end_date = dstr(ey, emo, ed)
    meal = MEAL[i % 3]
    st = "ACTIVE" if i < 30 else "DISABLED"
    rem = ["医生让每天早饭吃一片", "复查后医生加的药", "饭后半小时服用", None,
           "家属备注：老人记性不好，需要每天提醒"][i % 5]
    if i == 2:
        rem = ("省医院心内科复诊后医生开具，需长期服用，不可自行停药或减量；老人同时合并高血压、2 型糖尿病与"
               "慢性肾功能不全，每次复诊须带上全部药盒给医生核对，避免重复用药或漏服；家属已按周分装到七天"
               "药盒中，仍需每餐后提醒，尤其早餐后最容易忘记；如出现头晕、乏力、下肢水肿等情况请记录并及时"
               "告知医生，不要自行调整剂量；血压与血糖建议每日早晚各测一次并记录在册，复诊时一并带给医生查看。")
    plan_tps[pid] = tps
    mp_rows.append((pid, elder_id, 801 + mi, med[0], ["1 片", "2 片", "1 粒", "半片", "10ml"][i % 5],
                    freq, jq(tps), start_date, end_date, meal, st, rem, created_by,
                    dt(sy, smo, sd, 10 + i % 8, i % 60, 0)))
for idx, r in enumerate(mp_rows):
    w("  (%d, %d, %d, %s, %s, %d, %s, %s, %s, %s, %s, %s, %d, %s)%s" % (
        r[0], r[1], r[2], q(r[3]), q(r[4]), r[5], q(r[6]), q(r[7]), q(r[8]),
        q(r[9]), q(r[10]), q(r[11]), r[12], q(r[13]),
        "," if idx < len(mp_rows) - 1 else ";"))
w()

# ============================================================ 16. medication_task
w("-- =============================================================================")
w("-- 16. medication_task —— 每日服药任务（由用药计划按时间点展开）")
w("--     覆盖：已服 / 待服 / 漏服 / 漏服后补记（was_missed = 1）")
w("--     唯一索引 uk_plan_time 保证重复触发不会产生重复行")
w("-- =============================================================================")
w("INSERT INTO `medication_task`")
w("  (`id`,`plan_id`,`elder_id`,`medicine_id`,`medicine_name`,`dosage`,`meal_relation`,")
w("   `plan_date`,`plan_time`,`status`,`was_missed`,`confirm_time`,`confirm_by`,")
w("   `confirm_remark`,`notify_sent`,`notify_time`) VALUES")
mt_rows = []
mtid = 20001
for r in mp_rows:
    pid, elder_id, med_id, med_name, dosage, freq = r[0], r[1], r[2], r[3], r[4], r[5]
    tps = plan_tps.get(pid, [])
    meal = r[9]
    st_plan = r[10]
    created_by = r[12]
    # 生成窗口
    if st_plan == "ACTIVE":
        base_y, base_mo, base_d = 2026, 9, 9
        days = 7
    else:
        base_y, base_mo, base_d = 2026, 8, 20
        days = 5
    for dd in range(days):
        y, mo, d = add_days(base_y, base_mo, base_d, dd)
        pd = dstr(y, mo, d)
        for tp in tps:
            hh, mi = int(tp[:2]), int(tp[3:])
            pt = dt(y, mo, d, hh, mi, 0)
            key = (pid, pt)
            if key in [(x[1], x[8]) for x in mt_rows]:
                continue
            # 状态判定
            if st_plan == "DISABLED":
                st, wm, cf, cb, crm, ns, nt = "MISSED", 0, None, None, None, 1, pt
            else:
                rnd = (mtid * 37) % 100
                if rnd < 72:
                    st, wm = "TAKEN", 0
                    cf = dt(y, mo, d, hh, min(59, mi + 3 + mtid % 8), 0)
                    cb = created_by
                    crm = ["按时服用", "家人提醒后服用", "今天外出，晚了 1 小时"][mtid % 3]
                    ns, nt = 0, None
                elif rnd < 88:
                    st, wm = "PENDING", 0
                    cf = cb = crm = None
                    ns, nt = 0, None
                else:
                    st, wm = "MISSED", 1
                    cf = dt(y, mo, d, hh + 2, mi, 0) if mtid % 3 == 0 else None
                    cb = created_by if cf else None
                    crm = "发现漏服后补记" if cf else None
                    ns, nt = 1, pt
            mt_rows.append((mtid, pid, elder_id, med_id, med_name, dosage, meal, pd, pt,
                            st, wm, cf, cb, crm, ns, nt))
            mtid += 1
for idx, r in enumerate(mt_rows):
    w("  (%d, %d, %d, %d, %s, %s, %s, %s, %s, %s, %d, %s, %s, %s, %d, %s)%s" % (
        r[0], r[1], r[2], r[3], q(r[4]), q(r[5]), q(r[6]), q(r[7]), q(r[8]), q(r[9]),
        r[10], q(r[11]), num(r[12]), q(r[13]), r[14], q(r[15]),
        "," if idx < len(mt_rows) - 1 else ";"))
w()

# ============================================================ 17. order_review
w("-- =============================================================================")
w("-- 17. order_review —— 订单评价（30 条，与 REVIEWED 订单一一对应）")
w("--     含匿名评价、陪诊员回复、被管理员判定无效（is_valid = 0）")
w("-- =============================================================================")
w("INSERT INTO `order_review`")
w("  (`id`,`order_id`,`order_no`,`family_id`,`elder_id`,`companion_id`,`score`,`tags`,")
w("   `content`,`is_anonymous`,`companion_reply`,`reply_time`,`is_valid`,`create_time`) VALUES")
rv_rows = []
rvid = 30001
reviewed = [oid for oid in ORDER_IDS if order_meta[oid]["st"] == "REVIEWED"]
TAG_SETS = [["准时", "耐心", "沟通清楚"], ["专业", "负责"], ["准时", "耐心"],
            ["沟通清楚"], ["态度好", "细心", "有经验"]]
for i, oid in enumerate(reviewed):
    m = order_meta[oid]
    score = [5, 5, 4, 5, 3, 5, 4, 2, 5, 4][i % 10]
    content = ["小李很耐心，全程陪着老人，取药排队也帮忙，家里人很放心。",
               "陪诊员准时到达，流程很熟，老人很满意。",
               "整体不错，就是比约定时间晚了十分钟。",
               "沟通清楚，及时在群里同步进度，省心。",
               "态度还可以，但对科室位置不太熟悉。",
               "非常负责，取药后还仔细核对了药名和用量，并拍照给我们确认。"][i % 6]
    if i == 7:
        content = ("这次体验很差，陪诊员迟到近一小时也没提前通知，老人一个人在门口等了很久；"
                   "中途还一直看手机，问路也不耐烦；取药环节漏拿了一盒，回家才发现，"
                   "第二天又跑了一趟医院。希望平台加强管理，别再出现这种情况。")
    reply = ["感谢认可，后续会继续保持。", "谢谢您的理解，下次会提前沟通。",
             None, "抱歉给您带来不便，已反馈并改进。"][i % 4]
    rv_rows.append((rvid, oid, m["order_no"], m["family"], m["elder"], m["companion"],
                    score, jq(TAG_SETS[i % len(TAG_SETS)]), content,
                    1 if i % 5 == 0 else 0, reply,
                    dt(2026, 9, 14, 20, i % 60, 0) if reply else None,
                    0 if i == 9 else 1, dt(2026, 9, 14, 20, i % 60, 0)))
    rvid += 1
for idx, r in enumerate(rv_rows):
    w("  (%d, %d, %s, %d, %d, %d, %d, %s, %s, %d, %s, %s, %d, %s)%s" % (
        r[0], r[1], q(r[2]), r[3], r[4], r[5], r[6], q(r[7]), q(r[8]), r[9], q(r[10]),
        q(r[11]), r[12], q(r[13]), "," if idx < len(rv_rows) - 1 else ";"))
w()

# ============================================================ 18. complaint
w("-- =============================================================================")
w("-- 18. complaint —— 投诉（32 条：待处理 / 处理中 / 已结案 / 已驳回 全覆盖）")
w("--     不物理删除；状态只可正向流转")
w("-- =============================================================================")
w("INSERT INTO `complaint`")
w("  (`id`,`order_id`,`order_no`,`complainant_id`,`complainant_role`,`target_user_id`,")
w("   `target_role`,`type`,`content`,`evidence`,`status`,`handle_admin_id`,`handle_result`,")
w("   `penalty_to_target`,`handle_time`,`create_time`) VALUES")
cpl_rows = []
cpid = 31001
COMPLAINT_TYPES = ["LATE", "ATTITUDE", "INCOMPLETE", "FEE_DISPUTE", "PRIVACY", "OTHER"]
COMPLAINT_CONTENTS = {
    "LATE": "陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号，只能重新排队等候。",
    "ATTITUDE": "陪诊过程中态度不好，老人反复问路时显得很不耐烦，中途还一直低头看手机。",
    "INCOMPLETE": "约定要协助取药并送到家，实际只送到医院门口就走了，药品由老人自己拎回家。",
    "FEE_DISPUTE": "线下结算时要求多付 50 元，说是加班费，但下单时并未说明有这笔费用。",
    "PRIVACY": "发现陪诊员把老人的病历照片发在了自己的微信朋友圈，未做任何打码处理。",
    "OTHER": "陪诊员临时更换了人员，来的不是下单时约定的那位，事先也没有通知家属。",
}
done_orders = [oid for oid in ORDER_IDS
               if order_meta[oid]["st"] in ("COMPLETED", "REVIEWED", "CANCELLED")]
for i in range(32):
    oid = done_orders[i % len(done_orders)]
    m = order_meta[oid]
    typ = COMPLAINT_TYPES[i % 6]
    content = COMPLAINT_CONTENTS[typ]
    if i == 3:
        content = ("陪诊员全程几乎都在低头玩手机，没有陪同老人进入诊室，医生交代的复诊时间和注意事项一句都没有"
                   "转达给家属；取药时还把两种药的名字看错，把降压药和降糖药的位置弄反，幸亏老人家属自己核对时"
                   "及时发现，否则后果不堪设想；排队缴费期间擅自离开，让 82 岁的老人独自在缴费窗口前站了近二十"
                   "分钟，期间老人因为不熟悉流程来回走了三趟；更严重的是，该陪诊员把老人的检查报告和病历本拍下来"
                   "发到了自己的微信群里，照片中姓名、身份证号、诊断信息、住院号全都清晰可见，完全没有做任何遮挡"
                   "处理，群里有几十个陌生人；家属在群里看到截图后立即要求删除，对方态度还很恶劣，说「又不是什么"
                   "见不得人的病」，拒绝道歉；事后平台客服也未能给出有效的处理方案，只是说会「了解一下」。"
                   "家属现已保存全部聊天记录、截图与通话录音作为证据，要求平台：一、对该陪诊员作出严肃处理并公示"
                   "结果；二、就其泄露老人隐私的行为出具书面说明并承担相应责任；三、退还本次全部服务费；"
                   "四、完善陪诊员的隐私保护培训与考核机制，避免其他老人遭遇同样的情况。")
    # 投诉人与被投诉人由订单关系推导
    if i % 3 == 0 and m["companion"] is not None:
        cp_id, cp_role = m["companion"], "COMPANION"
        tg_id, tg_role = m["family"], "FAMILY"
    else:
        cp_id, cp_role = m["family"], "FAMILY"
        tg_id, tg_role = m["companion"] or COMPANION_IDS[i % 24], "COMPANION"
    if i < 10:
        st, hr, ht, hid, pen = "PENDING", None, None, None, 0
    elif i < 16:
        st, hr, ht, hid, pen = "PROCESSING", None, None, 1, 0
    elif i < 27:
        st = "RESOLVED"
        hr = "经核实情况属实，已对陪诊员进行警告并扣减信用分，同时向投诉人致歉并说明后续改进措施。"
        ht, hid, pen = dt(2026, 9, 13, 10, i % 60, 0), 1, 1
    else:
        st = "REJECTED"
        hr = "经调取打卡记录与轨迹数据，陪诊员到达时间符合约定，投诉内容与事实不符，予以驳回。"
        ht, hid, pen = dt(2026, 9, 13, 15, i % 60, 0), 1, 0
    cy, cmo, cd = add_days(2026, 9, 5, i % 10)
    ev = jq(["/uploads/202609/cp%02d.png" % (i % 6 + 1)]) if i % 3 == 0 else None
    cpl_rows.append((cpid, oid, m["order_no"], cp_id, cp_role, tg_id, tg_role, typ,
                     content, ev, st, hid, hr, pen, ht, dt(cy, cmo, cd, 19, i % 60, 0)))
    cpid += 1
for idx, r in enumerate(cpl_rows):
    w("  (%d, %d, %s, %d, %s, %d, %s, %s, %s, %s, %s, %s, %s, %d, %s, %s)%s" % (
        r[0], r[1], q(r[2]), r[3], q(r[4]), r[5], q(r[6]), q(r[7]), q(r[8]),
        "NULL" if r[9] is None else q(r[9]), q(r[10]), num(r[11]), q(r[12]), r[13],
        q(r[14]), q(r[15]), "," if idx < len(cpl_rows) - 1 else ";"))
w()

# ============================================================ 19. internal_message
w("-- =============================================================================")
w("-- 19. internal_message —— 站内信（72 条，覆盖 9 种消息类型，含未读/已读）")
w("-- =============================================================================")
w("INSERT INTO `internal_message`")
w("  (`id`,`receiver_id`,`sender_id`,`type`,`title`,`content`,`biz_type`,`biz_id`,")
w("   `link_url`,`is_read`,`read_time`,`receiver_deleted`,`create_time`) VALUES")
im_rows = []
imid = 40001
MSG = [
    ("ORDER_CREATED", "新陪诊订单", "有一笔新订单 {no}（{vt} {hos}），请及时接单。", "ORDER"),
    ("ORDER_ACCEPTED", "订单已接单", "陪诊员{cn}已接下订单 {no}。", "ORDER"),
    ("ORDER_PROGRESS", "陪诊进度更新", "{node}：{rm}（订单 {no}）", "ORDER"),
    ("ORDER_COMPLETED", "服务已完成", "订单 {no} 已完成，感谢您的信任，欢迎评价。", "ORDER"),
    ("ORDER_CANCELLED", "订单已取消", "订单 {no} 已取消，原因：{reason}。", "ORDER"),
    ("AUDIT_RESULT", "资质审核结果", "您的陪诊员资质申请已通过审核，现在可以开始接单了。", "AUDIT"),
    ("MEDICATION_REMIND", "用药提醒", "{elder}的「{med}」在 {pt} 未确认服用，请及时关注。", "MEDICATION"),
    ("COMPLAINT_HANDLED", "投诉处理结果", "您提交的投诉（{no}）已处理完成，处理结果可在投诉详情中查看。", "COMPLAINT"),
    ("SYSTEM_NOTICE", "系统公告", "平台将于本周日凌晨 02:00-04:00 进行系统维护，期间可能短暂无法访问。", "SYSTEM"),
]
for i in range(72):
    t = MSG[i % len(MSG)]
    oid = ORDER_IDS[i % 60]
    m = order_meta[oid]
    if t[3] == "ORDER":
        if t[0] == "ORDER_CREATED":
            recv = COMPANION_IDS[i % 24]
        else:
            recv = m["family"]
    elif t[3] == "AUDIT":
        recv = COMPANION_IDS[i % 30]
    elif t[3] == "MEDICATION":
        recv = FAMILY_IDS[i % 30]
    elif t[3] == "COMPLAINT":
        recv = FAMILY_IDS[i % 30]
    else:
        recv = [ADMIN_IDS[0], FAMILY_IDS[i % 30], COMPANION_IDS[i % 30], ELDER_IDS[i % 30]][i % 4]
    title = t[1]
    body = (t[2].replace("{no}", m["order_no"]).replace("{vt}", m["visit"][:16])
            .replace("{hos}", "海南省人民医院").replace("{cn}", "李*")
            .replace("{node}", "到院").replace("{rm}", "已到达医院，正在取号")
            .replace("{reason}", "家属临时有事，改期再约")
            .replace("{elder}", "张*三").replace("{med}", "苯磺酸氨氯地平片")
            .replace("{pt}", "2026-09-14 08:00"))
    is_read = 0 if i % 3 else 1
    cy, cmo, cd = add_days(2026, 9, 1, i % 15)
    dtime = dt(cy, cmo, cd, 8 + i % 13, i % 60, 0)
    im_rows.append((imid, recv, None if t[3] == "SYSTEM" else ADMIN_IDS[0], t[0], title, body,
                    t[3], oid, "/family?orderId=%d" % oid, is_read,
                    dtime if is_read else None, 0, dtime))
    imid += 1
for idx, r in enumerate(im_rows):
    w("  (%d, %d, %s, %s, %s, %s, %s, %d, %s, %d, %s, %d, %s)%s" % (
        r[0], r[1], num(r[2]), q(r[3]), q(r[4]), q(r[5]), q(r[6]), r[7], q(r[8]),
        r[9], q(r[10]), r[11], q(r[12]), "," if idx < len(im_rows) - 1 else ";"))
w()

# ============================================================ 20. admin_oper_log
w("-- =============================================================================")
w("-- 20. admin_oper_log —— 管理员操作日志（34 条，只增不改不删）")
w("-- =============================================================================")
w("INSERT INTO `admin_oper_log`")
w("  (`id`,`operator_id`,`operator_name`,`oper_type`,`target_type`,`target_id`,`target_desc`,")
w("   `before_status`,`after_status`,`remark`,`request_url`,`request_method`,`ip`,`oper_time`) VALUES")
ol_rows = []
olid = 60001
for i in range(24):
    cid = COMPANION_IDS[i]
    ol_rows.append((olid, ADMIN_IDS[i % 2], "系统管理员" if i % 2 == 0 else "超级管理员",
                    "AUDIT_COMPANION", "COMPANION", 701 + i, "资质申请 #%d（%s）" % (701 + i, COMPANION_NAMES[i]),
                    "PENDING", "APPROVED", "材料齐全，予以通过",
                    "/api/admin/companion/audit/%d" % (701 + i), "POST",
                    "192.168.3.%d" % (10 + i % 5), dt(2026, 9, 2 + i % 10, 15, i % 60, 0)))
    olid += 1
for i in range(4):
    ol_rows.append((olid, ADMIN_IDS[i % 2], "系统管理员", "DISABLE_USER", "USER",
                    ELDER_IDS[i], "用户 %s" % ELDER_NAMES[i], "NORMAL", "DISABLED",
                    "多次恶意下单后取消，予以封禁",
                    "/api/admin/user/%d/disable" % ELDER_IDS[i], "POST",
                    "192.168.3.10", dt(2026, 9, 12 + i % 3, 11, i % 60, 0)))
    olid += 1
for i in range(3):
    ol_rows.append((olid, ADMIN_IDS[0], "系统管理员", "RESET_PASSWORD", "USER",
                    FAMILY_IDS[i + 10], "用户 %s" % FAMILY_NAMES[i + 10], None, None,
                    "用户来电请求重置密码，已核验身份",
                    "/api/admin/user/%d/reset-password" % FAMILY_IDS[i + 10], "POST",
                    "192.168.3.10", dt(2026, 9, 14, 9 + i, 20, 0)))
    olid += 1
ol_rows.append((olid, ADMIN_IDS[0], "系统管理员", "ARBITRATE_ORDER", "ORDER", 1059,
                "NL20260915000059", "IN_SERVICE", "CANCELLED",
                "经核实陪诊员迟到 40 分钟且未提前告知，本次订单取消，服务费不结算",
                "/api/admin/order/1059/arbitrate", "POST", "192.168.3.10", dt(2026, 9, 15, 10, 0, 0)))
olid += 1
for i in range(3):
    oid = cpl_rows[i + 16][1]
    cp_order_no = cpl_rows[i + 16][2]
    ol_rows.append((olid, ADMIN_IDS[1], "超级管理员", "HANDLE_COMPLAINT", "COMPLAINT",
                    31001 + i + 16, "投诉 #%d（订单 %s）" % (31001 + i + 16, cp_order_no),
                    "PENDING", "RESOLVED",
                    "情况属实，已对陪诊员警告并扣减信用分",
                    "/api/admin/complaint/%d/handle" % (31001 + i + 16), "POST",
                    "192.168.3.11", dt(2026, 9, 13, 10, i % 60, 0)))
    olid += 1
ol_rows.append((olid, ADMIN_IDS[0], "系统管理员", "PUBLISH_NOTICE", None, None, None,
                None, None, "发布系统维护公告",
                "/api/admin/notice", "POST", "192.168.3.10", dt(2026, 9, 13, 16, 30, 0)))
for idx, r in enumerate(ol_rows):
    w("  (%d, %d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)%s" % (
        r[0], r[1], q(r[2]), q(r[3]), q(r[4]), num(r[5]), q(r[6]), q(r[7]), q(r[8]),
        q(r[9]), q(r[10]), q(r[11]), q(r[12]), q(r[13]),
        "," if idx < len(ol_rows) - 1 else ";"))
w()
w("-- =============================================================================")
w("-- 种子数据结束")
w("-- =============================================================================")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

print("WROTE", OUT)
print("lines =", len(lines))
