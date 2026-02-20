document.addEventListener('DOMContentLoaded', async () => {
    const { Toast, Loading, Modal, Skeleton, Utils } = window.MaterialComponents;
    
    // 元素引用
    const queryForm = document.getElementById('queryForm');
    const queryKeywordInput = document.getElementById('queryKeyword');
    const queryBySelect = document.getElementById('queryBy');
    const queryTargetSelect = document.getElementById('queryTarget');
    const queryResultDiv = document.getElementById('queryResult');
    const queryBtn = document.getElementById('queryBtn');
    const queryBtnText = document.getElementById('queryBtnText');
    const bindForm = document.getElementById('bindForm');
    const playerIdentifierInput = document.getElementById('playerIdentifier');
    const qqNumberInput = document.getElementById('qqNumber');
    const bindBtn = document.getElementById('bindBtn');
    const bindBtnText = document.getElementById('bindBtnText');
    const tokenBtn = document.getElementById('tokenBtn');

    // API Token 管理
    function getApiToken() {
        const urlParams = new URLSearchParams(window.location.search);
        let token = urlParams.get('token');
        if (!token) {
            token = localStorage.getItem('apiToken');
        }
        return token;
    }

    function setApiToken(token) {
        localStorage.setItem('apiToken', token);
    }
    
    function showTokenModal() {
        const currentToken = getApiToken();
        const tokenInput = document.createElement('input');
        tokenInput.type = 'password';
        tokenInput.value = currentToken || '';
        tokenInput.placeholder = '请输入 API Token';
        tokenInput.className = 'form-control';
        tokenInput.style.width = '100%';
        tokenInput.style.padding = '10px 12px';
        tokenInput.style.border = '1px solid var(--border-color)';
        tokenInput.style.borderRadius = '4px';
        tokenInput.style.fontSize = '0.875rem';
        
        const content = document.createElement('div');
        content.style.padding = '8px 0';
        content.appendChild(tokenInput);
        
        Modal.show({
            title: 'API Token 设置',
            content: content,
            buttons: [
                {
                    text: '取消',
                    onClick: () => false
                },
                {
                    text: '保存',
                    primary: true,
                    onClick: () => {
                        const newToken = tokenInput.value.trim();
                        if (!newToken) {
                            Toast.error('Token 不能为空');
                            return false;
                        }
                        setApiToken(newToken);
                        Toast.success('Token 已保存');
                        checkTokenAndInit();
                        return true;
                    }
                }
            ],
            closable: true
        });
        
        // 聚焦输入框
        setTimeout(() => tokenInput.focus(), 100);
    }
    
    function checkTokenAndInit() {
    const apiToken = getApiToken();
        if (!apiToken) {
            showTokenModal();
            return false;
        }
        return true;
    }
    
    tokenBtn.addEventListener('click', showTokenModal);
    
    // 初始化检查 Token
    if (!checkTokenAndInit()) {
            return;
        }
    
    const apiToken = getApiToken();

    // 查询功能
    queryForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const keyword = queryKeywordInput.value.trim();
        const by = queryBySelect.value;
        const target = queryTargetSelect.value;

        if (!keyword) {
            Toast.error('关键词不能为空');
            return;
        }
        
        await performQuery(keyword, by, target);
    });

    async function performQuery(keyword, by, target) {
        Loading.button(queryBtn, true);
        queryBtnText.textContent = '查询中...';
        queryResultDiv.innerHTML = '';

        // 显示骨架屏
        const skeleton = document.createElement('div');
        skeleton.className = 'card';
        Skeleton.fill(skeleton, [
            { type: 'text', width: '60%', height: '1.5em' },
            { type: 'text', width: '80%', height: '1em' },
            { type: 'text', width: '70%', height: '1em' }
        ]);
        queryResultDiv.appendChild(skeleton);
        
        try {
            const response = await fetch(
                `/api/query?keyword=${encodeURIComponent(keyword)}&by=${by}&target=${target}`,
                {
            headers: { 'X-API-Token': apiToken }
                }
            );
            
            if (!response.ok) {
                if (response.status === 401) {
                    Toast.error('Token 无效，请重新设置');
                    showTokenModal();
                    throw new Error('Unauthorized');
                }
                const errorData = await response.json();
                throw new Error(errorData.error || `HTTP error! status: ${response.status}`);
            }
            
            const data = await response.json();
            queryResultDiv.innerHTML = '';
            renderQueryResult(data, target);
            Toast.success('查询成功');
            
        } catch (error) {
            console.error('Query failed:', error);
            queryResultDiv.innerHTML = '';
            if (error.message !== 'Unauthorized') {
                Toast.error(`查询失败: ${error.message}`);
            }
        } finally {
            Loading.button(queryBtn, false);
            queryBtnText.textContent = '查询';
        }
    }

    function renderQueryResult(data, target) {
        if (target === 'all' || target === 'player') {
            if (data.player) {
                renderPlayer(data.player);
            }
        }
        if (target === 'all' || target === 'bots') {
            if (data.bots) {
                renderBots(data.bots);
            }
        }
        if (target === 'all' || target === 'meta') {
            if (data.meta) {
                renderMeta(data.meta);
            }
        }
        
        if (queryResultDiv.children.length === 0) {
            const emptyCard = document.createElement('div');
            emptyCard.className = 'card empty-state';
            emptyCard.innerHTML = `
                <div class="empty-state-icon">🔍</div>
                <p>未找到相关数据</p>
            `;
            queryResultDiv.appendChild(emptyCard);
        }
    }

    function renderPlayer(player) {
        const playerCard = document.createElement('div');
        playerCard.className = 'card result-card';
        playerCard.innerHTML = `
            <div class="result-header">
            <h3>玩家信息</h3>
                <div class="result-actions">
                    <button class="btn-secondary btn-edit" data-uuid="${player.uuid}">编辑</button>
                    <button class="btn-unbind" data-uuid="${player.uuid}">解绑</button>
                </div>
            </div>
            <table class="data-table">
                <tr>
                    <th>UUID</th>
                    <td><code>${player.uuid}</code></td>
                </tr>
                <tr>
                    <th>名称</th>
                    <td>${player.name || 'N/A'}</td>
                </tr>
                <tr>
                    <th>QQ</th>
                    <td>${player.qq || '未绑定'}</td>
                </tr>
            </table>
        `;
        queryResultDiv.appendChild(playerCard);

        // 绑定事件
        playerCard.querySelector('.btn-unbind').addEventListener('click', () => {
            unbindPlayer(player.uuid, player.name);
        });
        
        playerCard.querySelector('.btn-edit').addEventListener('click', () => {
            window.location.href = `admin_edit_player.html?uuid=${player.uuid}&token=${apiToken}`;
        });
    }

    function renderBots(bots) {
        const botsCard = document.createElement('div');
        botsCard.className = 'card result-card';
        
        let botsHtml = `
            <div class="result-header">
                <h3>假人列表 (${bots.length})</h3>
            </div>
        `;
        
        if (bots.length === 0) {
            botsHtml += '<p class="empty-state">该玩家没有假人</p>';
        } else {
            botsHtml += '<table class="data-table">';
            botsHtml += '<thead><tr><th>假人名称</th><th>UUID</th><th>创建时间</th><th>操作</th></tr></thead>';
            botsHtml += '<tbody>';
            bots.forEach(bot => {
                const date = new Date(parseInt(bot.created_at));
                botsHtml += `
                    <tr>
                        <td><strong>${bot.bot_name}</strong></td>
                        <td><code>${bot.bot_uuid}</code></td>
                        <td>${date.toLocaleString('zh-CN')}</td>
                        <td>
                            <button class="btn-unbind btn-small" data-bot-name="${bot.bot_name}">删除</button>
                        </td>
                    </tr>
                `;
            });
            botsHtml += '</tbody></table>';
        }
        
        botsCard.innerHTML = botsHtml;
        queryResultDiv.appendChild(botsCard);

        // 绑定删除事件
        botsCard.querySelectorAll('.btn-unbind').forEach(button => {
            button.addEventListener('click', (e) => {
                const botName = e.target.getAttribute('data-bot-name');
                unbindBot(botName);
            });
        });
    }

    function renderMeta(meta) {
        const metaCard = document.createElement('div');
        metaCard.className = 'card result-card';
        
        const keys = Object.keys(meta);
        let metaHtml = `
            <div class="result-header">
                <h3>元数据 (${keys.length})</h3>
            </div>
        `;
        
        if (keys.length === 0) {
            metaHtml += '<p class="empty-state">没有元数据</p>';
        } else {
            metaHtml += '<table class="data-table">';
            metaHtml += '<thead><tr><th>字段名</th><th>值</th></tr></thead>';
            metaHtml += '<tbody>';
            keys.forEach(key => {
                metaHtml += `
                    <tr>
                        <td><strong>${key}</strong></td>
                        <td>${meta[key]}</td>
                    </tr>
                `;
            });
            metaHtml += '</tbody></table>';
        }
        
        metaCard.innerHTML = metaHtml;
        queryResultDiv.appendChild(metaCard);
    }
    
    // 解绑玩家
    async function unbindPlayer(uuid, name) {
        const confirmed = await new Promise(resolve => {
            Modal.confirm(
                `确定要解绑玩家 "${name || uuid}" 吗？此操作将同时删除其名下所有假人。`,
                () => resolve(true),
                () => resolve(false)
            );
        });
        
        if (!confirmed) return;
        
        // 在不同视图中查找对应按钮（搜索结果卡片或表格）
        const btn = document.querySelector(`[data-uuid="${uuid}"]`);
        if (btn) {
            Loading.button(btn, true);
        }
        
        try {
            const response = await fetch('/api/unbind', {
            method: 'POST',
                headers: { 
                    'Content-Type': 'application/json', 
                    'X-API-Token': apiToken 
                },
            body: JSON.stringify({ uuid: uuid })
            });
            
            const data = await response.json();
            
            if (response.ok && data.success) {
                Toast.success('玩家解绑成功');
                // 清空查询结果区域
                if (queryResultDiv) {
                    queryResultDiv.innerHTML = '';
                }
                // 重新执行上方的单次查询（如果有）
                if (typeof performQuery === 'function' && queryKeywordInput && queryBySelect && queryTargetSelect) {
                    const keyword = queryKeywordInput.value.trim();
                    const by = queryBySelect.value;
                    const target = queryTargetSelect.value;
                    if (keyword) {
                        await performQuery(keyword, by, target);
                    }
                }
                // 刷新下方玩家/假人表格数据
                if (typeof loadPlayersData === 'function') {
                    await loadPlayersData();
                }
                if (typeof loadBotsData === 'function') {
                    await loadBotsData();
                }
            } else {
                throw new Error(data.error || '未知错误');
            }
        } catch (error) {
            console.error('Error unbinding player:', error);
            Toast.error(`解绑操作失败: ${error.message}`);
        }
    }

    // 解绑假人
    async function unbindBot(botName) {
        const confirmed = await new Promise(resolve => {
            Modal.confirm(
                `确定要删除假人 "${botName}" 吗？`,
                () => resolve(true),
                () => resolve(false)
            );
        });
        
        if (!confirmed) return;

        try {
            const response = await fetch('/api/bot/unbind', {
            method: 'POST',
                headers: { 
                    'Content-Type': 'application/json', 
                    'X-API-Token': apiToken 
                },
            body: JSON.stringify({ bot_name: botName })
            });
            
            const data = await response.json();
            
            if (response.ok && data.success) {
                Toast.success('假人删除成功');
                // 重新执行查询
                const keyword = queryKeywordInput.value.trim();
                const by = queryBySelect.value;
                const target = queryTargetSelect.value;
                if (keyword) {
                    await performQuery(keyword, by, target);
                }
            } else {
                throw new Error(data.error || '未知错误');
            }
        } catch (error) {
            console.error('Error unbinding bot:', error);
            Toast.error(`删除假人操作失败: ${error.message}`);
        }
    }

    // 手动绑定
    bindForm.addEventListener('submit', async (event) => {
            event.preventDefault();
            const playerIdentifier = playerIdentifierInput.value.trim();
            const qqNumber = qqNumberInput.value.trim();

            if (!playerIdentifier || !qqNumber) {
            Toast.error('玩家标识符和QQ号码不能为空');
            return;
        }
        
        if (!Utils.validateQQ(qqNumber)) {
            Toast.error('QQ号格式不正确');
                return;
            }

        Loading.button(bindBtn, true);
        bindBtnText.textContent = '提交中...';
        
        try {
            const response = await fetch('/api/admin/bind', {
                method: 'POST',
                headers: { 
                    'Content-Type': 'application/json', 
                    'X-API-Token': apiToken 
                },
                body: JSON.stringify({ 
                    playerIdentifier: playerIdentifier, 
                    qq: parseInt(qqNumber) 
                })
            });
            
            const data = await response.json();
            
            if (response.ok && data.success) {
                Toast.success(data.message || '绑定成功');
                    playerIdentifierInput.value = '';
                    qqNumberInput.value = '';
                } else {
                    throw new Error(data.error || '未知错误');
                }
        } catch (error) {
                console.error('Error in admin bind:', error);
            Toast.error(`操作失败: ${error.message}`);
        } finally {
            Loading.button(bindBtn, false);
            bindBtnText.textContent = '提交绑定';
        }
    });
    
    // 输入提示（简单的防抖搜索建议）
    const debouncedQuery = Utils.debounce((value) => {
        // 这里可以添加搜索建议功能
        // 目前先留空，后续可以扩展
    }, 500);
    
    queryKeywordInput.addEventListener('input', (e) => {
        debouncedQuery(e.target.value);
    });

    // ==================== Tables Management ====================
    const playersTableBody = document.getElementById('playersTableBody');
    const botsTableBody = document.getElementById('botsTableBody');
    const playersSearch = document.getElementById('playersSearch');
    const botsSearch = document.getElementById('botsSearch');
    const playersPageSize = document.getElementById('playersPageSize');
    const botsPageSize = document.getElementById('botsPageSize');
    const playersPagination = document.getElementById('playersPagination');
    const botsPagination = document.getElementById('botsPagination');
    const exportCsvBtn = document.getElementById('exportCsvBtn');
    const importCsvInput = document.getElementById('importCsvInput');
    const tabButtons = document.querySelectorAll('.tab-btn');

    // Check if table elements exist
    if (!playersTableBody || !botsTableBody) {
        console.error('Table elements not found', {
            playersTableBody: !!playersTableBody,
            botsTableBody: !!botsTableBody
        });
        return;
    }

    // Initialize empty state
    playersTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">加载中...</td></tr>';
    botsTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">加载中...</td></tr>';

    // Tab switching
    tabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabName = btn.getAttribute('data-tab');
            if (!tabName) {
                console.error('Tab button missing data-tab attribute');
                return;
            }
            
            // Remove active class from all tabs
            tabButtons.forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            
            // Add active class to clicked tab
            btn.classList.add('active');
            const targetTab = document.getElementById(`${tabName}-tab`);
            if (targetTab) {
                targetTab.classList.add('active');
            } else {
                console.error(`Tab content not found: ${tabName}-tab`);
            }
        });
    });

    // Data storage
    let allPlayersData = [];
    let allBotsData = [];
    let filteredPlayersData = [];
    let filteredBotsData = [];
    let currentPlayersPage = 1;
    let currentBotsPage = 1;
    let playersPageSizeValue = 50;
    let botsPageSizeValue = 50;

    // Load players data
    async function loadPlayersData() {
        if (!apiToken) {
            console.error('API Token not available for loading players');
            if (playersTableBody) {
                playersTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">请先设置 API Token</td></tr>';
            }
            return;
        }
        const loadingOverlay = Loading.show('加载玩家数据...');
        try {
            const response = await fetch('/api/players', {
                headers: { 'X-API-Token': apiToken }
            });
            if (!response.ok) {
                if (response.status === 401) {
                    Toast.error('Token 无效，请重新设置');
                    showTokenModal();
                    throw new Error('Unauthorized');
                }
                const errorText = await response.text();
                console.error('API Error:', response.status, errorText);
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            const data = await response.json();
            console.log('Loaded players data:', data.length, 'items');
            allPlayersData = Array.isArray(data) ? data : [];
            filteredPlayersData = [...allPlayersData];
            console.log('Players data loaded:', allPlayersData.length, 'total players');
            renderPlayersTable();
        } catch (error) {
            console.error('Error loading players:', error);
            if (error.message !== 'Unauthorized') {
                Toast.error(`加载玩家数据失败: ${error.message}`);
            }
            // Show empty state
            if (playersTableBody) {
                playersTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">加载失败: ' + error.message + '</td></tr>';
            }
        } finally {
            Loading.hide(loadingOverlay);
        }
    }

    // Load bots data
    async function loadBotsData() {
        if (!apiToken) {
            console.error('API Token not available for loading bots');
            if (botsTableBody) {
                botsTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">请先设置 API Token</td></tr>';
            }
            return;
        }
        const loadingOverlay = Loading.show('加载假人数据...');
        try {
            const response = await fetch('/api/bots', {
                headers: { 'X-API-Token': apiToken }
            });
            if (!response.ok) {
                if (response.status === 401) {
                    Toast.error('Token 无效，请重新设置');
                    showTokenModal();
                    throw new Error('Unauthorized');
                }
                const errorText = await response.text();
                console.error('API Error:', response.status, errorText);
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            const data = await response.json();
            console.log('Loaded bots data:', data.length, 'items');
            allBotsData = Array.isArray(data) ? data : [];
            filteredBotsData = [...allBotsData];
            console.log('Bots data loaded:', allBotsData.length, 'total bots');
            renderBotsTable();
        } catch (error) {
            console.error('Error loading bots:', error);
            if (error.message !== 'Unauthorized') {
                Toast.error(`加载假人数据失败: ${error.message}`);
            }
            // Show empty state
            if (botsTableBody) {
                botsTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">加载失败: ' + error.message + '</td></tr>';
            }
        } finally {
            Loading.hide(loadingOverlay);
        }
    }

    // Search players
    if (playersSearch) {
        playersSearch.addEventListener('input', Utils.debounce((e) => {
            const keyword = e.target.value.toLowerCase().trim();
            if (keyword === '') {
                filteredPlayersData = [...allPlayersData];
            } else {
                filteredPlayersData = allPlayersData.filter(player => {
                    const name = (player.Name || '').toLowerCase();
                    const qq = (player.QQ || '').toString();
                    const uuid = (player.UUID || '').toLowerCase();
                    return name.includes(keyword) || qq.includes(keyword) || uuid.includes(keyword);
                });
            }
            currentPlayersPage = 1;
            renderPlayersTable();
        }, 300));
    }

    // Search bots
    if (botsSearch) {
        botsSearch.addEventListener('input', Utils.debounce((e) => {
            const keyword = e.target.value.toLowerCase().trim();
            if (keyword === '') {
                filteredBotsData = [...allBotsData];
            } else {
                filteredBotsData = allBotsData.filter(bot => {
                    const botName = (bot.bot_name || '').toLowerCase();
                    const ownerName = (bot.owner_name || '').toLowerCase();
                    const ownerQq = (bot.owner_qq || '').toString();
                    return botName.includes(keyword) || ownerName.includes(keyword) || ownerQq.includes(keyword);
                });
            }
            currentBotsPage = 1;
            renderBotsTable();
        }, 300));
    }

    // Page size change
    if (playersPageSize) {
        playersPageSize.addEventListener('change', (e) => {
            playersPageSizeValue = parseInt(e.target.value);
            currentPlayersPage = 1;
            renderPlayersTable();
        });
    }

    if (botsPageSize) {
        botsPageSize.addEventListener('change', (e) => {
            botsPageSizeValue = parseInt(e.target.value);
            currentBotsPage = 1;
            renderBotsTable();
        });
    }

    // Render players table
    function renderPlayersTable() {
        if (!playersTableBody) return;
        
        const start = (currentPlayersPage - 1) * playersPageSizeValue;
        const end = start + playersPageSizeValue;
        const pageData = filteredPlayersData.slice(start, end);
        const totalPages = Math.max(1, Math.ceil(filteredPlayersData.length / playersPageSizeValue));

        playersTableBody.innerHTML = '';
        if (pageData.length === 0) {
            playersTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">暂无数据</td></tr>';
        } else {
            pageData.forEach(player => {
                const row = document.createElement('tr');
                const createdDate = player.Created ? new Date(parseInt(player.Created)).toLocaleString('zh-CN') : 'N/A';
                // Count bots for this player
                const botCount = allBotsData.filter(bot => bot.owner_uuid === player.UUID).length;
                row.innerHTML = `
                    <td><code>${player.UUID || 'N/A'}</code></td>
                    <td>${player.Name || 'N/A'}</td>
                    <td>${player.QQ || '未绑定'}</td>
                    <td>${createdDate}</td>
                    <td>${botCount}</td>
                    <td class="table-actions-cell">
                        <button class="btn-secondary btn-small btn-edit" data-uuid="${player.UUID}">编辑</button>
                        <button class="btn-unbind btn-small" data-uuid="${player.UUID}">解绑</button>
                    </td>
                `;
                playersTableBody.appendChild(row);
            });
        }

        // Bind events
        playersTableBody.querySelectorAll('.btn-edit').forEach(btn => {
            btn.addEventListener('click', () => {
                const uuid = btn.getAttribute('data-uuid');
                window.location.href = `admin_edit_player.html?uuid=${uuid}&token=${apiToken}`;
            });
        });

        playersTableBody.querySelectorAll('.btn-unbind').forEach(btn => {
            btn.addEventListener('click', () => {
                const uuid = btn.getAttribute('data-uuid');
                const player = allPlayersData.find(p => p.UUID === uuid);
                unbindPlayer(uuid, player?.Name || uuid);
            });
        });

        // Render pagination
        if (playersPagination) {
            renderPagination(playersPagination, currentPlayersPage, totalPages, filteredPlayersData.length, playersPageSizeValue, (page) => {
                currentPlayersPage = page;
                renderPlayersTable();
            });
        }
    }

    // Render bots table
    function renderBotsTable() {
        if (!botsTableBody) return;
        
        const start = (currentBotsPage - 1) * botsPageSizeValue;
        const end = start + botsPageSizeValue;
        const pageData = filteredBotsData.slice(start, end);
        const totalPages = Math.max(1, Math.ceil(filteredBotsData.length / botsPageSizeValue));

        botsTableBody.innerHTML = '';
        if (pageData.length === 0) {
            botsTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">暂无数据</td></tr>';
        } else {
            pageData.forEach(bot => {
                const row = document.createElement('tr');
                const createdDate = bot.created_at ? new Date(parseInt(bot.created_at)).toLocaleString('zh-CN') : 'N/A';
                row.innerHTML = `
                    <td><strong>${bot.bot_name || 'N/A'}</strong></td>
                    <td><code>${bot.bot_uuid || 'N/A'}</code></td>
                    <td>${bot.owner_name || 'N/A'}</td>
                    <td>${bot.owner_qq || '0'}</td>
                    <td>${createdDate}</td>
                    <td class="table-actions-cell">
                        <button class="btn-secondary btn-small btn-edit" data-uuid="${bot.bot_uuid}">编辑</button>
                        <button class="btn-unbind btn-small" data-bot-name="${bot.bot_name}">解绑</button>
                    </td>
                `;
                botsTableBody.appendChild(row);
            });
        }

        // Bind events
        botsTableBody.querySelectorAll('.btn-edit').forEach(btn => {
            btn.addEventListener('click', () => {
                const uuid = btn.getAttribute('data-uuid');
                window.location.href = `admin_edit_player.html?uuid=${uuid}&token=${apiToken}`;
            });
        });

        botsTableBody.querySelectorAll('.btn-unbind').forEach(btn => {
            btn.addEventListener('click', () => {
                const botName = btn.getAttribute('data-bot-name');
                unbindBot(botName);
            });
        });

        // Render pagination
        if (botsPagination) {
            renderPagination(botsPagination, currentBotsPage, totalPages, filteredBotsData.length, botsPageSizeValue, (page) => {
                currentBotsPage = page;
                renderBotsTable();
            });
        }
    }

    // Render pagination
    function renderPagination(container, currentPage, totalPages, totalItems, pageSize, onPageChange) {
        if (!container) return;
        
        container.innerHTML = '';
        
        const info = document.createElement('div');
        info.className = 'pagination-info';
        const start = totalItems === 0 ? 0 : (currentPage - 1) * pageSize + 1;
        const end = Math.min(currentPage * pageSize, totalItems);
        info.textContent = `显示 ${start}-${end} / 共 ${totalItems} 条`;
        container.appendChild(info);

        const controls = document.createElement('div');
        controls.className = 'pagination-controls';

        // Previous button
        const prevBtn = document.createElement('button');
        prevBtn.className = 'pagination-btn';
        prevBtn.textContent = '上一页';
        prevBtn.disabled = currentPage === 1;
        prevBtn.onclick = () => {
            if (currentPage > 1) {
                onPageChange(currentPage - 1);
            }
        };
        controls.appendChild(prevBtn);

        // Page numbers
        const maxVisiblePages = 5;
        let startPage = Math.max(1, currentPage - Math.floor(maxVisiblePages / 2));
        let endPage = Math.min(totalPages, startPage + maxVisiblePages - 1);
        if (endPage - startPage < maxVisiblePages - 1) {
            startPage = Math.max(1, endPage - maxVisiblePages + 1);
        }

        if (startPage > 1) {
            const firstBtn = document.createElement('button');
            firstBtn.className = 'pagination-page';
            firstBtn.textContent = '1';
            firstBtn.onclick = () => onPageChange(1);
            controls.appendChild(firstBtn);
            if (startPage > 2) {
                const ellipsis = document.createElement('span');
                ellipsis.textContent = '...';
                ellipsis.style.padding = '0 8px';
                controls.appendChild(ellipsis);
            }
        }

        for (let i = startPage; i <= endPage; i++) {
            const pageBtn = document.createElement('button');
            pageBtn.className = 'pagination-page';
            if (i === currentPage) {
                pageBtn.classList.add('active');
            }
            pageBtn.textContent = i.toString();
            pageBtn.onclick = () => onPageChange(i);
            controls.appendChild(pageBtn);
        }

        if (endPage < totalPages) {
            if (endPage < totalPages - 1) {
                const ellipsis = document.createElement('span');
                ellipsis.textContent = '...';
                ellipsis.style.padding = '0 8px';
                controls.appendChild(ellipsis);
            }
            const lastBtn = document.createElement('button');
            lastBtn.className = 'pagination-page';
            lastBtn.textContent = totalPages.toString();
            lastBtn.onclick = () => onPageChange(totalPages);
            controls.appendChild(lastBtn);
        }

        // Next button
        const nextBtn = document.createElement('button');
        nextBtn.className = 'pagination-btn';
        nextBtn.textContent = '下一页';
        nextBtn.disabled = currentPage === totalPages;
        nextBtn.onclick = () => {
            if (currentPage < totalPages) {
                onPageChange(currentPage + 1);
            }
        };
        controls.appendChild(nextBtn);

        container.appendChild(controls);
    }

    // CSV Export
    if (exportCsvBtn) {
        exportCsvBtn.addEventListener('click', async () => {
        Loading.button(exportCsvBtn, true);
        try {
            const csvTypeSelect = document.getElementById('csvTypeSelect');
            const csvType = csvTypeSelect ? csvTypeSelect.value : 'players';
            const typeParam = csvType === 'bots' ? 'bots' : 'players';
            
            const response = await fetch(`/api/csv/export?type=${typeParam}`, {
                headers: { 'X-API-Token': apiToken }
            });
            if (!response.ok) {
                throw new Error('导出失败');
            }
            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            const fileName = csvType === 'bots' ? 'bots' : 'players';
            a.download = `${fileName}_${new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5)}.csv`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            window.URL.revokeObjectURL(url);
            Toast.success('CSV 导出成功');
        } catch (error) {
            console.error('Error exporting CSV:', error);
            Toast.error(`导出失败: ${error.message}`);
        } finally {
            Loading.button(exportCsvBtn, false);
        }
        });
    }

    // CSV Import
    if (importCsvInput) {
        importCsvInput.addEventListener('change', async (e) => {
        const file = e.target.files[0];
        if (!file) {
            return;
        }

        if (!file.name.endsWith('.csv')) {
            Toast.error('请选择 CSV 文件');
            e.target.value = '';
            return;
        }

        if (file.size > 10 * 1024 * 1024) {
            Toast.error('文件大小不能超过 10MB');
            e.target.value = '';
            return;
        }

        const confirmed = await new Promise(resolve => {
            Modal.confirm(
                `确定要导入文件 "${file.name}" 吗？这将覆盖现有数据。`,
                () => resolve(true),
                () => resolve(false)
            );
        });

        if (!confirmed) {
            e.target.value = '';
            return;
        }

        const loadingOverlay = Loading.show('正在导入CSV...');
        try {
            const csvTypeSelect = document.getElementById('csvTypeSelect');
            const csvType = csvTypeSelect ? csvTypeSelect.value : 'players';
            const typeParam = csvType === 'bots' ? 'bots' : 'players';
            
            // Read file as text with UTF-8 encoding
            const csvText = await file.text();
            
            const response = await fetch(`/api/csv/import?type=${typeParam}`, {
                method: 'POST',
                headers: { 
                    'X-API-Token': apiToken,
                    'Content-Type': 'text/csv; charset=utf-8'
                },
                body: csvText
            });

            const result = await response.json();
            if (response.ok && result.success) {
                Toast.success(result.message || '导入成功');
                // Reload data
                await loadPlayersData();
                await loadBotsData();
            } else {
                throw new Error(result.error || '导入失败');
            }
        } catch (error) {
            console.error('Error importing CSV:', error);
            Toast.error(`导入失败: ${error.message}`);
        } finally {
            Loading.hide(loadingOverlay);
            e.target.value = '';
        }
        });
    }

    // Initial load - load bots first so we can count them for players
    // Only load if API token is available
    console.log('Initializing tables, API Token available:', !!apiToken);
    if (apiToken) {
        try {
            await loadBotsData();
            await loadPlayersData();
        } catch (error) {
            console.error('Error during initial data load:', error);
        }
    } else {
        // Show message if no token
        console.log('No API Token, showing placeholder');
        if (playersTableBody) {
            playersTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">请先设置 API Token</td></tr>';
        }
        if (botsTableBody) {
            botsTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 32px; color: var(--text-secondary);">请先设置 API Token</td></tr>';
        }
    }
});
