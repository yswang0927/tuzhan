import { create } from 'zustand';
import type { TrajectoryData } from "../common/types";

export interface TrajectoryState {
    trajectoryData: TrajectoryData[];
    tableLoading: boolean;
    setTrajectoryData: (data: TrajectoryData[]) => void;
    setTableLoading: (loading: boolean) => void;
    reset: () => void;
}

export const useTrajectoryStore = create<TrajectoryState>((set) => ({
    trajectoryData: [],
    tableLoading: false,
    setTrajectoryData: (data) => set({ trajectoryData: data }),
    setTableLoading: (loading) => set({ tableLoading: loading }),
    reset: () => set({ trajectoryData: [], tableLoading: false }),
}));
