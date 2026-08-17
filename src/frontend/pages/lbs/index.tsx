import { useSearchParams } from "react-router-dom";
import { Toaster } from 'sonner';

import TrajectoryQuery from "./TrajectoryQuery";

import "./style.css";

export default function LBS() {
    const [searchParams, setSearchParams] = useSearchParams();

    return (
        <div className="absolute inset-0">
            <TrajectoryQuery />
            <Toaster position="top-center" />
        </div>
    );
}