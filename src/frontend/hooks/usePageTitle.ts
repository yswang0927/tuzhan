import { useEffect, useRef } from "react";

/**
 * @param title 页面标题，传 undefined 使用路由默认标题
 */
export default function usePageTitle(title?: string) {
  const originTitleRef = useRef(document.title);

  useEffect(() => {
    if (title) {
      document.title = title;
    }
    // 组件卸载恢复原有标题（可选，根据业务取舍）
    return () => {
      document.title = originTitleRef.current;
    };
  }, [title]);
}