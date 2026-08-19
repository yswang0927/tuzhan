import { useState, useEffect, useCallback, useRef } from 'react';

const BASE_API_URL = 'http://127.0.0.1:9090';

/**
 * get 请求封装，直接返回后端data字段
 * @param {string} url
 * @param {Object} [params] query参数对象
 * @returns {Promise<any>} 返回后端response.data
 */
export async function getJson(
    url: string,
    params: Record<string, any>,
    signal?: AbortSignal
) {
    let queryUrl = url;
    if (params && typeof params === 'object') {
        const sp = new URLSearchParams(params);
        queryUrl += (url.includes('?') ? '&' : '?') + sp.toString();
    }

    const res = await fetch(`${BASE_API_URL}${queryUrl}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        signal: signal
    });

    // http层面错误，如404 500
    if (!res.ok) {
        if(res.status === 401){
            // 跳登录页
            location.href = '/login'
            return;
        }
        throw new Error(`HTTP ${res.status}`);
    }

    const json = await res.json();
    // 业务code判断：code !==0 业务失败
    if (json.code !== 0) {
        // 把后端message抛出，上层catch捕获
        throw new Error(json.message || '业务请求失败');
    }

    return json.data;
}

export async function postJson(
    url: string,
    body: Record<string, any>,
    signal?: AbortSignal
) {
    const res = await fetch(`${BASE_API_URL}${url}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        credentials: 'include',
        signal: signal
    });

    if (!res.ok) {
        if(res.status === 401){
            // 跳登录页
            location.href = '/login'
            return;
        }
        throw new Error(`HTTP ${res.status}`);
    }

    const json = await res.json();
    if (json.code !== 0) {
        throw new Error(json.message || '业务请求失败');
    }

    return json.data;
}

/* ============ useFetch() ============ */

export interface UseFetchOptions {
    method?: 'GET' | 'POST';
    params?: Record<string, any>;
    immediate?: boolean;
    pollingInterval?: number;
    debounceInterval?: number;
    throttleInterval?: number;
    refreshOnWindowFocus?: boolean;

    // 指数退避重试配置
    retryCount?: number;
    retryInterval?: number;
    maxRetryInterval?: number;
    retryBackoff?: boolean;
    retryJitter?: boolean;
}

export interface UseFetchReturn<T> {
    data: T | null;
    loading: boolean;
    error: Error | null;
    refetch: (newParams?: Record<string, any>) => Promise<T | undefined>;
    run: (newParams?: Record<string, any>) => Promise<T | undefined>;
    abort: () => void;
}

export function useFetch<T = any>(
    url: string,
    options: UseFetchOptions = {}
): UseFetchReturn<T> {
    const {
        method = 'GET',
        params,
        immediate = true,
        pollingInterval = 0,
        debounceInterval = 0,
        throttleInterval = 0,
        refreshOnWindowFocus = false,
    } = options;

    const [data, setData] = useState<T | null>(null);
    const [loading, setLoading] = useState<boolean>(immediate);
    const [error, setError] = useState<Error | null>(null);

    const optionsRef = useRef(options);
    optionsRef.current = options;

    const paramsRef = useRef(params);
    paramsRef.current = params;

    const abortControllerRef = useRef<AbortController | null>(null);
    const pollingTimerRef = useRef<number | null>(null);
    const debounceTimerRef = useRef<number | null>(null);
    const throttleTimerRef = useRef<number | null>(null);
    const retryTimerRef = useRef<number | null>(null);
    const isThrottledRef = useRef<boolean>(false);

    const clearTimers = useCallback(() => {
        if (pollingTimerRef.current) clearTimeout(pollingTimerRef.current);
        if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
        if (throttleTimerRef.current) clearTimeout(throttleTimerRef.current);
        if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    }, []);

    // 核心请求（含指数退避重试）
    const executeCore = useCallback(
        async (
            overrideParams?: Record<string, any>,
            currentRetry = 0
        ): Promise<T | undefined> => {
            if (abortControllerRef.current) {
                abortControllerRef.current.abort();
            }

            const controller = new AbortController();
            abortControllerRef.current = controller;

            setLoading(true);
            if (currentRetry === 0) {
                setError(null);
            }

            const requestParams = overrideParams ?? paramsRef.current ?? {};
            const {
                method = 'GET',
                pollingInterval,
                retryCount = 0,
                retryInterval = 1000,
                maxRetryInterval = 30000,
                retryBackoff = true,
                retryJitter = false,
            } = optionsRef.current;

            try {
                const result = (method === 'GET')
                        ? await getJson<T>(url, requestParams, controller.signal)
                        : await postJson<T>(url, requestParams, controller.signal);

                if (controller.signal.aborted) {
                    return;
                }

                setData(result);
                setError(null);
                setLoading(false);

                // 如果启用了轮寻
                if (pollingInterval && pollingInterval > 0) {
                    pollingTimerRef.current = setTimeout(() => {
                        executeCore(overrideParams, 0);
                    }, pollingInterval);
                }

                return result;
            } catch (err: any) {
                if ((typeof err === 'object' && err.name === 'AbortError') || controller.signal.aborted) {
                    return;
                }

                const errorObj = err instanceof Error ? err : new Error(String(err));

                // 触发重试逻辑
                if (currentRetry < retryCount) {
                    // 1. 指数退避: base * 2^(currentRetry)
                    let delay = retryBackoff
                        ? retryInterval * Math.pow(2, currentRetry)
                        : retryInterval;

                    // 2. 封顶最大延迟
                    delay = Math.min(delay, maxRetryInterval);

                    // 3. 可选：全抖动 (Full Jitter)
                    if (retryJitter) {
                        delay = Math.floor(Math.random() * delay);
                    }

                    retryTimerRef.current = setTimeout(() => {
                        executeCore(overrideParams, currentRetry + 1);
                    }, delay);
                } else {
                    // 重试次数用尽
                    setError(errorObj);
                    setLoading(false);

                    if (pollingInterval && pollingInterval > 0) {
                        pollingTimerRef.current = setTimeout(() => {
                            executeCore(overrideParams, 0);
                        }, pollingInterval);
                    }
                }
            }
        },
        [url]
    );

    // 防抖 / 节流 调度
    const execute = useCallback(
        (overrideParams?: Record<string, any>): Promise<T | undefined> => {
            const { debounceInterval, throttleInterval } = optionsRef.current;

            if (debounceInterval && debounceInterval > 0) {
                return new Promise((resolve) => {
                    if (debounceTimerRef.current) {
                        clearTimeout(debounceTimerRef.current);
                    }
                    debounceTimerRef.current = setTimeout(async () => {
                        const res = await executeCore(overrideParams);
                        resolve(res);
                    }, debounceInterval);
                });
            }

            if (throttleInterval && throttleInterval > 0) {
                return new Promise((resolve) => {
                    if (isThrottledRef.current) {
                        return;
                    }
                    isThrottledRef.current = true;
                    throttleTimerRef.current = setTimeout(() => {
                        isThrottledRef.current = false;
                    }, throttleInterval);

                    executeCore(overrideParams).then(resolve);
                });
            }

            return executeCore(overrideParams);
        },
        [executeCore]
    );

    // 挂载执行
    useEffect(() => {
        if (immediate) {
            executeCore();
        }

        return () => {
            clearTimers();
            if (abortControllerRef.current) {
                abortControllerRef.current.abort();
            }
        };
    }, [url, immediate, executeCore, clearTimers]);

    // 聚焦重连
    useEffect(() => {
        if (!refreshOnWindowFocus) {
            return;
        }

        const handleFocus = () => {
            if (document.visibilityState === 'visible') {
                executeCore();
            }
        };

        window.addEventListener('visibilitychange', handleFocus);
        window.addEventListener('focus', handleFocus);

        return () => {
            window.removeEventListener('visibilitychange', handleFocus);
            window.removeEventListener('focus', handleFocus);
        };
    }, [refreshOnWindowFocus, executeCore]);

    const abort = useCallback(() => {
        clearTimers();
        abortControllerRef.current?.abort();
        setLoading(false);
    }, [clearTimers]);

    return {
        data,
        loading,
        error,
        refetch: execute,
        run: execute,
        abort,
    };
}
