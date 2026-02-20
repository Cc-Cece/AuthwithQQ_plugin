document.addEventListener('DOMContentLoaded', () => {
    const { Toast, Loading, Skeleton, Utils } = window.MaterialComponents;
    
    const profileForm = document.getElementById('profileForm');
    const tokenInput = document.getElementById('token');
    const uuidInput = document.getElementById('uuid');
    const playerNameInput = document.getElementById('playerName');
    const currentQqInput = document.getElementById('currentQq');
    const newQqInput = document.getElementById('newQq');
    const qqError = document.getElementById('qqError');
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const submitBtn = document.getElementById('submitBtn');
    const submitBtnText = document.getElementById('submitBtnText');

    // 解析 URL 参数
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');

    if (!token) {
        Toast.error('缺少会话令牌，请重新获取链接');
        setTimeout(() => {
            window.location.href = 'dashboard.html';
        }, 2000);
        return;
    }
    tokenInput.value = token;

    // 显示骨架屏
    function showSkeleton() {
        customFieldsContainer.innerHTML = '';
        for (let i = 0; i < 3; i++) {
            const skeleton = Skeleton.create('text', '100%', '3em');
            customFieldsContainer.appendChild(skeleton);
        }
    }

    // 获取个人资料数据
    async function fetchProfile() {
        showSkeleton();
        const loadingOverlay = Loading.show('加载个人资料...');
        
        try {
            const response = await fetch(`/api/profile?token=${token}`);
            
            if (response.status === 401) {
                throw new Error('无效或已过期的会话令牌');
            }
            
            if (!response.ok) {
                throw new Error('获取个人资料失败');
            }
            
            const profile = await response.json();
            
            uuidInput.value = profile.uuid;
            playerNameInput.value = profile.name || 'N/A';
            currentQqInput.value = profile.qq || '未绑定';
            newQqInput.value = profile.qq || ''; // 预填充当前QQ
            
            // 清空自定义字段容器
            customFieldsContainer.innerHTML = '';

            // 获取自定义字段定义
            const metaResponse = await fetch('/api/meta');
            if (!metaResponse.ok) {
                throw new Error('获取自定义字段定义失败');
            }
            
            const customFieldsDefs = await metaResponse.json();
            
            if (customFieldsDefs.length === 0) {
                const emptyMsg = document.createElement('p');
                emptyMsg.className = 'empty-state';
                emptyMsg.textContent = '暂无自定义字段';
                customFieldsContainer.appendChild(emptyMsg);
            } else {
                customFieldsDefs.forEach(fieldDef => {
                    const formGroup = document.createElement('div');
                    formGroup.className = 'form-group';

                    const label = document.createElement('label');
                    label.setAttribute('for', fieldDef.name);
                    label.textContent = fieldDef.label + (fieldDef.required ? ' *' : '');
                    formGroup.appendChild(label);

                    const input = document.createElement('input');
                    input.setAttribute('type', fieldDef.type || 'text');
                    input.setAttribute('id', fieldDef.name);
                    input.setAttribute('name', fieldDef.name);
                    input.setAttribute('placeholder', `请输入${fieldDef.label}`);
                    
                    if (fieldDef.required) {
                        input.setAttribute('required', 'true');
                    }
                    
                    // 预填充现有数据
                    if (profile.meta && profile.meta[fieldDef.name]) {
                        input.value = profile.meta[fieldDef.name];
                    }
                    
                    formGroup.appendChild(input);
                    
                    // 添加提示
                    const hint = document.createElement('span');
                    hint.className = 'input-hint';
                    hint.textContent = fieldDef.required ? '此字段为必填项' : '此字段为选填项';
                    formGroup.appendChild(hint);
                    
                    customFieldsContainer.appendChild(formGroup);
                });
            }
            
            Loading.hide(loadingOverlay);
            Toast.success('个人资料加载成功');
            
        } catch (error) {
            Loading.hide(loadingOverlay);
            console.error('Error fetching profile:', error);
            Toast.error(`加载失败: ${error.message}`);
            
            customFieldsContainer.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">⚠</div>
                    <p>${error.message}</p>
                    <button class="btn-secondary" onclick="location.reload()">重试</button>
                </div>
            `;
        }
    }

    // QQ 号验证
    const validateQQ = Utils.debounce((value) => {
        const qqStr = String(value).trim();
        
        if (!qqStr) {
            qqError.style.display = 'none';
            newQqInput.classList.remove('error', 'success');
            return true; // 允许为空（不修改）
        }
        
        if (Utils.validateQQ(qqStr)) {
            qqError.style.display = 'none';
            newQqInput.classList.remove('error');
            newQqInput.classList.add('success');
            return true;
        } else {
            qqError.textContent = 'QQ号格式不正确，请输入5-12位数字';
            qqError.style.display = 'block';
            newQqInput.classList.remove('success');
            newQqInput.classList.add('error');
            return false;
        }
    }, 300);

    newQqInput.addEventListener('input', (e) => {
        validateQQ(e.target.value);
    });

    newQqInput.addEventListener('blur', (e) => {
        validateQQ(e.target.value);
    });

    // 表单提交处理
    profileForm.addEventListener('submit', async (event) => {
        event.preventDefault();

        // 验证 QQ 号
        const newQqValue = newQqInput.value.trim();
        if (newQqValue && !Utils.validateQQ(newQqValue)) {
            Toast.error('请输入有效的QQ号');
            newQqInput.focus();
            return;
        }

        // 收集数据
        const data = {
            token: tokenInput.value,
            qq: newQqValue ? parseInt(newQqValue) : parseInt(currentQqInput.value) || 0,
            meta: {}
        };

        // 收集自定义字段
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            if (input.name !== 'newQq') {
                const value = input.value.trim();
                if (value) {
                    data.meta[input.name] = value;
                }
            }
        });
        
        // 验证必填字段
        const customFields = customFieldsContainer.querySelectorAll('input[required]');
        for (const field of customFields) {
            if (!field.value.trim()) {
                const label = field.previousElementSibling.textContent.replace(' *', '');
                Toast.error(`请填写必填项: ${label}`);
                field.focus();
                return;
            }
        }

        // 提交数据
        Loading.button(submitBtn, true);
        submitBtnText.textContent = '保存中...';

        try {
            const response = await fetch('/api/profile/update', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(data)
            });

            const result = await response.json();

            if (result.success) {
                Toast.success(result.message || '个人资料更新成功');
                // 刷新数据
                setTimeout(() => {
                    fetchProfile();
                }, 500);
            } else {
                Toast.error(result.error || '更新失败');
            }
        } catch (error) {
            console.error('Error updating profile:', error);
            Toast.error(`更新失败: ${error.message}`);
        } finally {
            Loading.button(submitBtn, false);
            submitBtnText.textContent = '保存修改';
        }
    });

    // 初始加载
    fetchProfile();
});
