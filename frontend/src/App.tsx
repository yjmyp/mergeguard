import { HashRouter, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { OverviewPage } from "./pages/Overview";
import { ReviewsPage } from "./pages/Reviews";
import { ReviewDetailPage } from "./pages/ReviewDetail";
import { ReposPage } from "./pages/Repos";

export function App() {
  return (
    <HashRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/reviews" element={<ReviewsPage />} />
          <Route path="/reviews/:id" element={<ReviewDetailPage />} />
          <Route path="/repos" element={<ReposPage />} />
        </Route>
      </Routes>
    </HashRouter>
  );
}
