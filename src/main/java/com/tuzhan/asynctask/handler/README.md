新增一种分析能力时，只需：
1. 实现一个 AsyncTaskHandler
2. 用 @Component 标注，Spring 会自动注入到 `AsyncTaskServiceImpl.handlersMap`
