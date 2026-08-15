import { useSearchParams } from "react-router-dom";

import TrajectoryQuery from "./TrajectoryQuery";

import "./style.css";

export default function LBS() {
    const [searchParams, setSearchParams] = useSearchParams();

    return (
        <TrajectoryQuery />
    );
}