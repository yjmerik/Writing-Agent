import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Layout, Typography } from 'antd';
import ProjectListPage from './pages/ProjectListPage';
import ProjectWorkspace from './pages/ProjectWorkspace';

const { Header, Content } = Layout;
const { Title } = Typography;

export default function App() {
  return (
    <HashRouter>
      <Layout style={{ minHeight: '100vh' }}>
        <Header style={{ display: 'flex', alignItems: 'center', background: '#001529' }}>
          <Title level={3} style={{ color: '#fff', margin: 0 }}>
            📖 长篇小说写作系统
          </Title>
        </Header>
        <Content style={{ padding: '24px' }}>
          <Routes>
            <Route path="/" element={<ProjectListPage />} />
            <Route path="/project/:id" element={<ProjectWorkspace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Content>
      </Layout>
    </HashRouter>
  );
}
