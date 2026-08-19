import { useState, useEffect, useCallback, useRef } from 'react';

const BASE_API_URL = (window as any).BASE_URL || 'http://127.0.0.1:9090';

/**
 * get 请求封装，直接返回后端data字段
 * @param {string} url
 * @param {Object} [params] query参数对象
 * @returns {Promise<any>} 返回后端response.data
 */
export async function getJson<T = any>(
    url: string,
    params?: Record<string, any>,
    signal?: AbortSignal
): Promise<T> {
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
            location.href = '/login';
            throw new Error('HTTP 401');
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

export async function postJson<T = any>(
    url: string,
    body: Record<string, any>,
    signal?: AbortSignal
): Promise<T> {
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
            location.href = '/login';
            throw new Error('HTTP 401');
        }
        throw new Error(`HTTP ${res.status}`);
    }

    const json = await res.json();
    if (json.code !== 0) {
        throw new Error(json.message || '业务请求失败');
    }

    return json.data;
}

// 安全调用生命周期回调，回调内部抛错不影响请求主流程
function safeCall(fn: ((...args: any[]) => void) | undefined, ...args: any[]) {
    if (typeof fn !== 'function') return;
    try {
        fn(...args);
    } catch (e) {
        console.error('[useFetch] lifecycle callback error:', e);
    }
}

/**
 * ============ useFetch() ============
 * 用法：
 * const {data, loading, error} = useFetch('/api/xxx');
 * 
 * 手动触发：
 * const {data, loading, error, run} = useFetch('/api/xxx', {immediate:false});
 * <button onClick={run} disabled={loading}>
 *  {loading ? 'Loading' : 'Edit'}
 * </button>
 * 
 * 轮寻：
 * const { data, run, abort } = useFetch('/api/xxx', { 
 *  pollingInterval: 3000
 * });
 * 
 * 防抖：
 * const { data, run, abort } = useFetch('/api/xxx', { 
 *  debounceInterval: 300
 * });
 * 
 * 节流：
 * const { data, run, abort } = useFetch('/api/xxx', { 
 *  throttleInterval: 300
 * });
 * 
 * 错误重试：
 * const { data, run, abort } = useFetch('/api/xxx', { 
 *  retryCount: 3,
 *  retryInterval: 1000,
 *  maxRetryInterval: 10000
 * });
 */
export interface UseFetchOptions<T = any> {
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

    // 生命周期回调
    onBefore?: (params: Record<string, any>) => void;
    onSuccess?: (data: T, params: Record<string, any>) => void;
    onError?: (error: Error, params: Record<string, any>) => void;
    onFinally?: (params: Record<string, any>, data?: T, error?: Error) => void;
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
    options: UseFetchOptions<T> = {}
): UseFetchReturn<T> {
    const {
        params,
        immediate = true,
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

            // 发起新请求前清理挂起的轮询/重试定时器，避免定时器泄漏与请求链翻倍
            if (pollingTimerRef.current) {
                clearTimeout(pollingTimerRef.current);
                pollingTimerRef.current = null;
            }
            if (retryTimerRef.current) {
                clearTimeout(retryTimerRef.current);
                retryTimerRef.current = null;
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
                onBefore,
                onSuccess,
                onError,
                onFinally,
            } = optionsRef.current;

            // 请求前回调（仅在首次尝试触发，重试不重复触发）
            if (currentRetry === 0) {
                safeCall(onBefore, requestParams);
            }

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

                // 成功回调
                safeCall(onSuccess, result, requestParams);
                safeCall(onFinally, requestParams, result, undefined);

                // 如果启用了轮寻
                if (pollingInterval && pollingInterval > 0) {
                    pollingTimerRef.current = setTimeout(() => {
                        executeCore(overrideParams, 0);
                    }, pollingInterval);
                }

                return result;

            } catch (err: any) {
                if ((typeof err === 'object' && err.name === 'AbortError') 
                    || controller.signal.aborted) {
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

                    // 失败回调
                    safeCall(onError, errorObj, requestParams);
                    safeCall(onFinally, requestParams, undefined, errorObj);

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
                        resolve(undefined);
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
