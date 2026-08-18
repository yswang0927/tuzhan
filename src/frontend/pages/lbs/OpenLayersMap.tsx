import { useRef, useEffect } from "react";
import Map from 'ol/Map.js';
import View from 'ol/View.js';
import Attribution from 'ol/control/Attribution.js';
import { defaults as defaultControls } from 'ol/control/defaults.js';
import { fromLonLat } from 'ol/proj.js';
import 'ol/ol.css';
import { apply } from 'ol-mapbox-style';

export function OpenLayersMap() {
    const mapDomRef = useRef<HTMLDivElement|null>(null);

    useEffect(() => {
        if (!mapDomRef.current) {
            return;
        }

        const key = 'Mncog8HkQPxDHlnvb2kI';
        const styleJson = `https://api.maptiler.com/maps/base-v4/style.json?key=${key}`;

        const attribution = new Attribution({
            collapsible: false,
        });

        const map = new Map({
            target: mapDomRef.current,
            controls: defaultControls({attribution: false}).extend([attribution]),
            view: new View({
                constrainResolution: true,
                center: fromLonLat([0, 0]),
                zoom: 1
            })
        });
        apply(map, styleJson);
    }, []);

    return (
        <div className="relative w-full h-full">
            <div ref={mapDomRef} className="absolute inset-0"></div>
        </div>
    );
}