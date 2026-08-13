import { useSearchParams } from "react-router-dom";

import GeoMap from "./map";
import PersonTrajectoryLocation from "./PersonTrajectoryLocation";

import "./style.css";

export default function LBS() {
    const [searchParams, setSearchParams] = useSearchParams();

    return (
        <PersonTrajectoryLocation />
    );
}