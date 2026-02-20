document.addEventListener('DOMContentLoaded', () => {
    const { Toast, Loading, Skeleton } = window.MaterialComponents;
    const botForm = document.getElementById('botForm');
    const botUuidInput = document.getElementById('botUuid');
    const botNameInput = document.getElementById('botName');
    const botSubtitle = document.getElementById('botSubtitle');
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const noFieldsMessage = document.getElementById('noFieldsMessage');
    const submitBtn = document.getElementById('submitBtn');
    const submitBtnText = document.getElementById('submitBtnText');

    // Get session token and bot UUID
    const sessionToken = localStorage.getItem('session_token');
    const urlParams = new URLSearchParams(window.location.search);
    const botUuid = urlParams.get('bot_uuid');

    if (!sessionToken) {
        Toast.error('请先登录');
        setTimeout(() => window.location.href = 'login.html', 1500);
        return;
    }

    if (!botUuid) {
        Toast.error('缺少假人参数');
        setTimeout(() => window.location.href = 'player_dashboard.html', 1500);
        return;
    }

    botUuidInput.value = botUuid;

    // Show skeleton loading
    function showSkeleton() {
        customFieldsContainer.innerHTML = '';
        for (let i = 0; i < 2; i++) {
            const skeleton = Skeleton.create('text', '100%', '3em');
            customFieldsContainer.appendChild(skeleton);
        }
    }

    // Load bot profile
    async function loadBotProfile() {
        showSkeleton();
        
        try {
            // Fetch bot profile
            const profileResponse = await fetch(`/api/user/bot/profile?bot_uuid=${botUuid}`, {
                headers: { 'X-Session-Token': sessionToken }
            });

            if (profileResponse.status === 401) {
                Toast.error('登录已过期，请重新登录');
                localStorage.removeItem('session_token');
                setTimeout(() => window.location.href = 'login.html', 1500);
                return;
            }

            if (profileResponse.status === 403) {
                Toast.error('您不是该假人的所有者');
                setTimeout(() => window.location.href = 'player_dashboard.html', 1500);
                return;
            }

            if (!profileResponse.ok) {
                throw new Error('获取假人资料失败');
            }

            const profile = await profileResponse.json();

            // Populate basic info
            botNameInput.value = profile.name || '';
            botSubtitle.textContent = `编辑假人「${profile.name || '未知'}」的自定义信息`;

            // Clear container
            customFieldsContainer.innerHTML = '';

            // Fetch custom field definitions for bots
            const metaResponse = await fetch('/api/meta?type=bot');
            if (!metaResponse.ok) {
                throw new Error('获取自定义字段定义失败');
            }

            const customFieldsDefs = await metaResponse.json();

            if (customFieldsDefs.length === 0) {
                noFieldsMessage.style.display = 'block';
                submitBtn.style.display = 'none';
            } else {
                noFieldsMessage.style.display = 'none';
                submitBtn.style.display = 'block';

                customFieldsDefs.forEach(fieldDef => {
                    const formGroup = document.createElement('div');
                    formGroup.className = 'form-group';

                    const label = document.createElement('label');
                    label.setAttribute('for', fieldDef.name);
                    label.textContent = fieldDef.label || fieldDef.name;
                    if (fieldDef.required) {
                        label.textContent += ' *';
                    }
                    formGroup.appendChild(label);

                    const input = document.createElement('input');
                    input.type = fieldDef.type || 'text';
                    input.id = fieldDef.name;
                    input.name = fieldDef.name;
                    input.placeholder = fieldDef.placeholder || '';
                    if (fieldDef.required) {
                        input.required = true;
                    }
                    // Populate with existing value
                    if (profile.meta && profile.meta[fieldDef.name]) {
                        input.value = profile.meta[fieldDef.name];
                    }
                    formGroup.appendChild(input);

                    const hint = document.createElement('span');
                    hint.className = 'input-hint';
                    hint.textContent = fieldDef.required ? '此字段为必填项' : '此字段为选填项';
                    formGroup.appendChild(hint);

                    customFieldsContainer.appendChild(formGroup);
                });
            }

        } catch (error) {
            console.error('Error fetching bot profile:', error);
            Toast.error(`加载失败: ${error.message}`);

            customFieldsContainer.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">⚠</div>
                    <p>${error.message}</p>
                    <button class="btn btn-secondary" onclick="location.reload()">重试</button>
                </div>
            `;
        }
    }

    // Form submission
    botForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        // Collect custom field data
        const meta = {};
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            const value = input.value.trim();
            meta[input.name] = value;
        });

        // Validate required fields
        const requiredFields = customFieldsContainer.querySelectorAll('input[required]');
        for (const field of requiredFields) {
            if (!field.value.trim()) {
                const label = field.previousElementSibling.textContent.replace(' *', '');
                Toast.error(`请填写必填项: ${label}`);
                field.focus();
                return;
            }
        }

        // Disable button and show loading
        submitBtn.disabled = true;
        submitBtnText.textContent = '保存中...';

        try {
            const response = await fetch('/api/user/bot/update', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Session-Token': sessionToken
                },
                body: JSON.stringify({
                    bot_uuid: botUuid,
                    meta: meta
                })
            });

            const data = await response.json();

            if (data.success) {
                Toast.success('假人资料更新成功！');
                setTimeout(() => window.location.href = 'player_dashboard.html', 1500);
            } else {
                throw new Error(data.error || '更新失败');
            }
        } catch (error) {
            console.error('Error updating bot profile:', error);
            Toast.error(`更新失败: ${error.message}`);
        } finally {
            submitBtn.disabled = false;
            submitBtnText.textContent = '保存修改';
        }
    });

    // Initialize
    loadBotProfile();
});
