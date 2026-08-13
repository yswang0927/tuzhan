import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import Home from '@/pages/home';
import LBS from "@/pages/lbs";

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/lbs" element={<LBS />} />
                <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
        </BrowserRouter>
    )
}

export default App;