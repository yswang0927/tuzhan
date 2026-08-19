import { create } from 'zustand';
import type { OpenLayersMapHandle } from '@/pages/common/OpenLayersMap';
import type { TrajectoryData } from '@/pages/common/types';

interface HomeState {
    mainMenu: string;
    setMainMenu: (menu: string) => void;

    activeSubMenuId: string | null;
    setActiveSubMenuId: (id: string | null) => void;

    // 地图命令句柄，供任意子组件调用地图 API
    mapApi: OpenLayersMapHandle | null;
    setMapApi: (api: OpenLayersMapHandle | null) => void;

    // 查询到的轨迹数据，供底部表格展示(查询面板与表格分处不同组件树，用 store 桥接)
    trajectoryData: TrajectoryData[];
    setTrajectoryData: (data: TrajectoryData[]) => void;
    tableLoading: boolean;
    setTableLoading: (loading: boolean) => void;
}

export const useHomeStore = create<HomeState>((set) => ({
    mainMenu: 'trajectory',
    setMainMenu: (menu) => set({ mainMenu: menu }),

    activeSubMenuId: null,
    setActiveSubMenuId: (id) => set({ activeSubMenuId: id }),

    mapApi: null,
    setMapApi: (api) => set({ mapApi: api }),

    trajectoryData: [],
    setTrajectoryData: (data) => set({ trajectoryData: data }),
    tableLoading: false,
    setTableLoading: (loading) => set({ tableLoading: loading }),
}));
