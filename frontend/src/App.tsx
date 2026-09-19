import { HashRouter, Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Typography, Button, Space } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import ProjectListPage from './pages/ProjectListPage';
import ProjectWorkspace from './pages/ProjectWorkspace';
import ModelConfigPage from './pages/ModelConfigPage';

const { Header, Content } = Layout;
const { Title } = Typography;

function HeaderNav() {
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', background: '#001529' }}>
      <Title level={3} style={{ color: '#fff', margin: 0, cursor: 'pointer' }} onClick={() => navigate('/')}>
        📖 长篇小说写作系统
      </Title>
      <Space>
        {location.pathname === '/' && (
          <Button ghost icon={<SettingOutlined />} onClick={() => navigate('/models')}>
            模型配置
          </Button>
        )}
        {location.pathname !== '/' && (
          <Button ghost onClick={() => navigate('/')}>
            返回项目列表
          </Button>
        )}
      </Space>
    </Header>
  );
}

export default function App() {
  return (
    <HashRouter>
      <Layout style={{ minHeight: '100vh' }}>
        <HeaderNav />
        <Content style={{ padding: '24px' }}>
          <Routes>
            <Route path="/" element={<ProjectListPage />} />
            <Route path="/models" element={<ModelConfigPage />} />
            <Route path="/project/:id" element={<ProjectWorkspace />} />
            <Route path="/project/:id/chapter/:chapterIndex" element={<ProjectWorkspace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Content>
      </Layout>
    </HashRouter>
  );
}
