document.addEventListener('DOMContentLoaded', () => {
    const { Toast, Loading, Utils } = window.MaterialComponents;
    const authForm = document.getElementById('authForm');
    const uuidInput = document.getElementById('uuid');
    const nameInput = document.getElementById('name');
    const verificationCodeInput = document.getElementById('verificationCode');
    const codeValueDisplay = document.getElementById('codeValue');
    const copyCodeBtn = document.getElementById('copyCodeBtn');
    const copyBtnText = document.getElementById('copyBtnText');
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const qqInput = document.getElementById('qq');
    const qqHint = document.getElementById('qqHint');
    const qqError = document.getElementById('qqError');
    const submitBtn = document.getElementById('submitBtn');
    const submitBtnText = document.getElementById('submitBtnText');
    const countdownEl = document.getElementById('countdown');
    const countdownText = document.getElementById('countdownText');

    // 解析 URL 参数
    const urlParams = new URLSearchParams(window.location.search);
    const uuid = urlParams.get('uuid');
    const name = urlParams.get('name');
    const verificationCode = urlParams.get('verificationCode');

    // 验证码过期时间（默认5分钟）
    const CODE_EXPIRATION = 300; // 秒
    let codeExpirationTime = null;
    let countdownInterval = null;

    // 初始化
    if (uuid) {
        uuidInput.value = uuid;
    }
    if (name) {
        nameInput.value = decodeURIComponent(name);
    }
    if (verificationCode) {
        verificationCodeInput.value = verificationCode;
        codeValueDisplay.textContent = verificationCode;
        // 设置过期时间
        codeExpirationTime = Date.now() + (CODE_EXPIRATION * 1000);
        startCountdown();
    } else {
        codeValueDisplay.textContent = '未提供验证码';
        countdownEl.style.display = 'none';
    }

    // 倒计时功能
    function startCountdown() {
        if (countdownInterval) {
            clearInterval(countdownInterval);
        }
        
        countdownInterval = setInterval(() => {
            if (!codeExpirationTime) return;
            
            const remaining = Math.max(0, Math.floor((codeExpirationTime - Date.now()) / 1000));
            
            if (remaining === 0) {
                clearInterval(countdownInterval);
                countdownText.textContent = '验证码已过期，请重新获取';
                countdownEl.classList.add('expiring');
                codeValueDisplay.textContent = '已过期';
                Toast.warning('验证码已过期，请返回游戏重新获取');
            } else {
                const formatted = Utils.formatTime(remaining);
                countdownText.textContent = `验证码有效期: ${formatted}`;
                
                // 最后30秒警告
                if (remaining <= 30) {
                    countdownEl.classList.add('expiring');
                } else {
                    countdownEl.classList.remove('expiring');
                }
            }
        }, 1000);
    }

    // 复制验证码功能
    copyCodeBtn.addEventListener('click', async () => {
        const code = verificationCodeInput.value;
        if (!code) {
            Toast.error('验证码不存在');
            return;
        }
        
        const success = await Utils.copyToClipboard(code);
        if (success) {
            copyBtnText.textContent = '已复制';
            Toast.success('验证码已复制到剪贴板');
            setTimeout(() => {
                copyBtnText.textContent = '复制';
            }, 2000);
        } else {
            Toast.error('复制失败，请手动复制');
        }
    });

    // QQ 号实时验证
    const validateQQ = Utils.debounce((value) => {
        const qqStr = String(value).trim();
        
        if (!qqStr) {
            qqError.style.display = 'none';
            qqInput.classList.remove('error', 'success');
            return false;
        }
        
        if (Utils.validateQQ(qqStr)) {
            qqError.style.display = 'none';
            qqInput.classList.remove('error');
            qqInput.classList.add('success');
            return true;
        } else {
            qqError.textContent = 'QQ号格式不正确，请输入5-12位数字';
            qqError.style.display = 'block';
            qqInput.classList.remove('success');
            qqInput.classList.add('error');
            return false;
        }
    }, 300);

    qqInput.addEventListener('input', (e) => {
        validateQQ(e.target.value);
    });

    qqInput.addEventListener('blur', (e) => {
        validateQQ(e.target.value);
    });

    // 获取自定义字段
    const loadingOverlay = Loading.show('加载表单字段...');
    fetch('/api/meta')
        .then(response => {
            if (!response.ok) {
                throw new Error('获取自定义字段失败');
            }
            return response.json();
        })
        .then(customFields => {
            Loading.hide(loadingOverlay);
            
            if (customFields.length === 0) {
                return;
            }
            
            customFields.forEach(field => {
                const formGroup = document.createElement('div');
                formGroup.className = 'form-group';

                const label = document.createElement('label');
                label.setAttribute('for', field.name);
                label.textContent = field.label + (field.required ? ' *' : '');
                formGroup.appendChild(label);

                const input = document.createElement('input');
                input.setAttribute('type', field.type || 'text');
                input.setAttribute('id', field.name);
                input.setAttribute('name', field.name);
                input.setAttribute('placeholder', `请输入${field.label}`);
                
                if (field.required) {
                    input.setAttribute('required', 'true');
                }
                
                // 添加验证提示
                const hint = document.createElement('span');
                hint.className = 'input-hint';
                hint.textContent = field.required ? '此字段为必填项' : '此字段为选填项';
                formGroup.appendChild(input);
                formGroup.appendChild(hint);

                customFieldsContainer.appendChild(formGroup);
            });
        })
        .catch(error => {
            Loading.hide(loadingOverlay);
            console.error('Error fetching custom fields:', error);
            Toast.error('加载自定义字段失败，但您仍可以继续绑定');
        });

    // 表单提交处理
    authForm.addEventListener('submit', async (event) => {
        event.preventDefault();

        // 验证验证码是否过期
        if (codeExpirationTime && Date.now() > codeExpirationTime) {
            Toast.error('验证码已过期，请返回游戏重新获取');
            return;
        }

        // 验证 QQ 号
        const qqValue = qqInput.value.trim();
        if (!Utils.validateQQ(qqValue)) {
            Toast.error('请输入有效的QQ号');
            qqInput.focus();
            return;
        }

        // 收集表单数据
        const formData = new FormData(authForm);
        const data = {
            uuid: formData.get('uuid'),
            code: formData.get('code'),
            qq: parseInt(qqValue),
            meta: {}
        };

        // 收集自定义字段
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            if (input.name !== 'uuid' && input.name !== 'qq' && input.name !== 'name' && input.name !== 'code') {
                const value = input.value.trim();
                if (value) {
                    data.meta[input.name] = value;
                }
            }
        });

        // 验证必填的自定义字段
        const customFields = customFieldsContainer.querySelectorAll('input[required]');
        for (const field of customFields) {
            if (!field.value.trim()) {
                Toast.error(`请填写必填项: ${field.previousElementSibling.textContent.replace(' *', '')}`);
                field.focus();
                return;
            }
        }

        // 提交数据
        Loading.button(submitBtn, true);
        submitBtnText.textContent = '提交中...';

        try {
            const response = await fetch('/api/bind', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(data)
            });

            const result = await response.json();

            if (result.success) {
                Toast.success('绑定成功！正在跳转...');
                setTimeout(() => {
                    window.location.href = 'success.html';
                }, 1000);
            } else {
                Loading.button(submitBtn, false);
                submitBtnText.textContent = '提交绑定';
                Toast.error(result.error || '绑定失败，请检查信息后重试');
            }
        } catch (error) {
            Loading.button(submitBtn, false);
            submitBtnText.textContent = '提交绑定';
            console.error('Error during binding:', error);
            Toast.error('网络错误，请稍后再试');
        }
    });

    // 清理定时器
    window.addEventListener('beforeunload', () => {
        if (countdownInterval) {
            clearInterval(countdownInterval);
        }
    });
});
