import { create } from 'zustand';

interface HomeState {
    mainMenu: string;
    setMainMenu: (menu: string) => void;

    activeSubMenuId: string | null;
    setActiveSubMenuId: (id: string | null) => void;
}

export const useHomeStore = create<HomeState>((set) => ({
    mainMenu: 'trajectory',
    setMainMenu: (menu) => set({ mainMenu: menu }),

    activeSubMenuId: null,
    setActiveSubMenuId: (id) => set({ activeSubMenuId: id }),
}));
