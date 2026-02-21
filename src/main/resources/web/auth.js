document.addEventListener('DOMContentLoaded', () => {
    const { Toast, Loading, Utils } = window.MaterialComponents;
    const authForm = document.getElementById('authForm');
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
    const name = urlParams.get('name');
    const verificationCode = urlParams.get('verificationCode');
    
    // 验证码过期时间：优先从 URL 读取，否则使用默认 300 秒
    const expireParam = urlParams.get('expire');
    const CODE_EXPIRATION = expireParam ? parseInt(expireParam, 10) : 300; // 秒
    let codeExpirationTime = null;
    let countdownInterval = null;

    // 初始化 - 必须从 URL 参数中获取 name 和 verificationCode
    if (name) {
        nameInput.value = decodeURIComponent(name);
    } else {
        // 如果没有 name 参数，显示错误提示
        console.error('Missing name parameter in URL');
        Toast.error('缺少玩家名参数，请从游戏内获取正确的绑定链接');
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

    // 获取自定义字段（玩家类型）
    const loadingOverlay = Loading.show('加载表单字段...');
    fetch('/api/meta?type=player')
        .then(response => {
            if (!response.ok) {
                throw new Error('获取自定义字段失败');
            }
            return response.json();
        })
        .then(customFields => {
            Loading.hide(loadingOverlay);
            
            if (customFields.length === 0) {
                // 如果没有自定义字段，隐藏容器并添加类以居中主卡片
                customFieldsContainer.style.display = 'none';
                const authLayout = document.querySelector('.auth-layout');
                if (authLayout) {
                    authLayout.classList.add('no-custom-fields');
                }
                return;
            }
            
            // 显示自定义字段容器
            customFieldsContainer.style.display = 'block';
            
            // 创建自定义字段卡片（所有字段在一个卡片中）
            const fieldsCard = document.createElement('div');
            fieldsCard.className = 'custom-fields-card card';
            
            // 卡片标题
            const cardTitle = document.createElement('h3');
            cardTitle.textContent = '附加信息';
            fieldsCard.appendChild(cardTitle);
            
            // 为每个自定义字段创建表单组
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
                
                fieldsCard.appendChild(formGroup);
            });
            
            // 将卡片添加到容器
            customFieldsContainer.appendChild(fieldsCard);
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

        // 收集表单数据 - 直接从输入框获取，不依赖 formData
        // 确保 nameInput 存在且有值
        if (!nameInput) {
            Toast.error('页面加载错误：找不到玩家名输入框');
            console.error('nameInput is null');
            return;
        }
        
        // 优先从 URL 参数获取 name，如果输入框为空
        let nameValue = nameInput.value ? nameInput.value.trim() : '';
        if (!nameValue) {
            // 尝试从 URL 参数获取
            const urlName = urlParams.get('name');
            if (urlName) {
                nameValue = decodeURIComponent(urlName);
                nameInput.value = nameValue;
            }
        }
        
        console.log('Name input value:', nameInput.value, 'Trimmed:', nameValue, 'URL name:', urlParams.get('name'));
        
        if (!nameValue) {
            Toast.error('玩家名不能为空，请从游戏内获取正确的绑定链接');
            console.error('Name input is empty. URL params:', window.location.search);
            nameInput.focus();
            return;
        }
        
        if (!verificationCodeInput) {
            Toast.error('页面加载错误：找不到验证码输入框');
            console.error('verificationCodeInput is null');
            return;
        }
        
        const codeValue = verificationCodeInput.value ? verificationCodeInput.value.trim() : '';
        console.log('Code input value:', verificationCodeInput.value, 'Trimmed:', codeValue);
        
        if (!codeValue) {
            Toast.error('验证码不能为空');
            console.error('Code input is empty');
            verificationCodeInput.focus();
            return;
        }
        
        // 构建数据对象，明确只包含需要的字段，不包含 uuid
        // 使用 Object.create(null) 确保没有原型链上的属性
        const data = Object.create(null);
        data.name = nameValue;
        data.code = codeValue;
        data.qq = parseInt(qqValue);
        data.meta = Object.create(null);

        // 收集自定义字段
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            // 明确排除 uuid、qq、name、code 字段
            const inputName = input.name;
            if (inputName && inputName !== 'uuid' && inputName !== 'qq' && inputName !== 'name' && inputName !== 'code') {
                const value = input.value ? input.value.trim() : '';
                if (value) {
                    data.meta[inputName] = value;
                }
            }
        });

        // 验证必填的自定义字段
        const customFields = customFieldsContainer.querySelectorAll('input[required]');
        for (const field of customFields) {
            if (!field.value || !field.value.trim()) {
                Toast.error(`请填写必填项: ${field.previousElementSibling.textContent.replace(' *', '')}`);
                field.focus();
                return;
            }
        }
        
        // 调试：输出实际发送的数据
        console.log('Submitting data:', JSON.stringify(data, null, 2));
        console.log('Data object keys:', Object.keys(data));
        console.log('Data.name:', data.name);
        console.log('Data has uuid?', 'uuid' in data);

        // 提交数据
        Loading.button(submitBtn, true);
        submitBtnText.textContent = '提交中...';

        try {
            // 确保 body 是 JSON 字符串，不包含 uuid
            const requestBody = JSON.stringify(data);
            console.log('Request body:', requestBody);
            
            const response = await fetch('/api/bind', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: requestBody
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
