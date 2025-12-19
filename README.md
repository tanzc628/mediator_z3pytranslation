# Mediator_z3pytranslation

## 概述
本仓库在[mediator的release版本](https://github.com/mediator-team/mediator.git)的基础上扩建形成，目的是实现从mediator到z3py的转化。

目前实现了基本的类型支持，函数支持，状态机翻译以及LTL性质验证等功能，可以对相当一部分的mediator模型进行验证。详细可见Introduction.md，由Gemini3Pro，暂时还没有人工查看，但应该大差不差。

目前已知的一些显著缺陷：
- function的template功能未实现，主要由于还未考虑完全泛型的处理方式
- 对typedef的处理存在一些不明的问题，比如在后文中无法识别前文定义的type，不过这似乎是解析器层的问题
- 复杂变量的相关操作存在受限于扁平化处理的一系列局限性
- property支持相对有限，无法面对实际软件性质验证复杂场景。后续考虑利用QBF的方式完成对CTL*的支持
- 待补充


