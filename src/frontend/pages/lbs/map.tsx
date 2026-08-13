import { useEffect, useRef } from "react";

export default function GeoMap() {
    const mapDomRef = useRef(null);
    const mapRef = useRef(null);

    useEffect(() => {
        (window as any).AMapLoader.load({
            key: "d39920f829c920ba5e6d14abbd52e88f",
            version: "2.0",
            plugins:["AMap.Scale"]
        }).then((AMap) => {
            const map = mapRef.current = new AMap.Map(mapDomRef.current);
        }).catch((e) => {
            console.error(e); //加载错误提示
        });
    });

    return (
        <div className="relative w-full h-full">
            <div ref={mapDomRef} className="absolute inset-0"></div>
        </div>
    );
}