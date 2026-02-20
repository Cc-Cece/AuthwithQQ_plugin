/**
 * Material Design Components - Lightweight, Framework-free
 * 通用组件库，不依赖任何框架
 */

// ==================== Toast 通知组件 ====================
const Toast = {
    /**
     * 显示 Toast 通知
     * @param {string} message - 消息内容
     * @param {string} type - 类型: 'success', 'error', 'info', 'warning'
     * @param {number} duration - 显示时长（毫秒），默认 3000
     */
    show(message, type = 'info', duration = 3000) {
        // 移除已存在的 Toast
        const existing = document.querySelector('.toast-container');
        if (existing) {
            existing.remove();
        }

        // 创建容器
        const container = document.createElement('div');
        container.className = 'toast-container';
        
        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        
        // 图标
        const icon = document.createElement('span');
        icon.className = 'toast-icon';
        icon.textContent = this.getIcon(type);
        
        // 消息
        const text = document.createElement('span');
        text.className = 'toast-message';
        text.textContent = message;
        
        toast.appendChild(icon);
        toast.appendChild(text);
        container.appendChild(toast);
        document.body.appendChild(container);
        
        // 触发动画
        requestAnimationFrame(() => {
            toast.classList.add('toast-show');
        });
        
        // 自动移除
        setTimeout(() => {
            toast.classList.remove('toast-show');
            setTimeout(() => container.remove(), 300);
        }, duration);
    },
    
    getIcon(type) {
        const icons = {
            success: '✓',
            error: '✕',
            info: 'ℹ',
            warning: '⚠'
        };
        return icons[type] || icons.info;
    },
    
    success(message, duration) {
        this.show(message, 'success', duration);
    },
    
    error(message, duration) {
        this.show(message, 'error', duration || 4000);
    },
    
    info(message, duration) {
        this.show(message, 'info', duration);
    },
    
    warning(message, duration) {
        this.show(message, 'warning', duration);
    }
};

// ==================== Loading 加载组件 ====================
const Loading = {
    /**
     * 显示加载遮罩
     * @param {string} message - 加载提示文字
     * @returns {HTMLElement} 返回容器元素，用于后续移除
     */
    show(message = '加载中...') {
        const overlay = document.createElement('div');
        overlay.className = 'loading-overlay';
        
        const spinner = document.createElement('div');
        spinner.className = 'loading-spinner';
        spinner.innerHTML = `
            <div class="spinner-circle"></div>
            <div class="spinner-circle"></div>
            <div class="spinner-circle"></div>
        `;
        
        const text = document.createElement('div');
        text.className = 'loading-text';
        text.textContent = message;
        
        overlay.appendChild(spinner);
        overlay.appendChild(text);
        document.body.appendChild(overlay);
        
        return overlay;
    },
    
    /**
     * 隐藏加载遮罩
     * @param {HTMLElement} overlay - Loading.show() 返回的元素
     */
    hide(overlay) {
        if (overlay && overlay.parentNode) {
            overlay.classList.add('loading-fade-out');
            setTimeout(() => overlay.remove(), 300);
        }
    },
    
    /**
     * 按钮加载状态
     * @param {HTMLElement} button - 按钮元素
     * @param {boolean} isLoading - 是否加载中
     */
    button(button, isLoading) {
        if (isLoading) {
            button.disabled = true;
            button.dataset.originalText = button.textContent;
            button.innerHTML = '<span class="button-spinner"></span> ' + button.textContent;
        } else {
            button.disabled = false;
            button.textContent = button.dataset.originalText || button.textContent;
        }
    }
};

// ==================== Modal 模态框组件 ====================
const Modal = {
    /**
     * 显示模态框
     * @param {Object} options - 配置选项
     * @param {string} options.title - 标题
     * @param {string|HTMLElement} options.content - 内容（HTML字符串或元素）
     * @param {Array} options.buttons - 按钮配置 [{text, onClick, primary}]
     * @param {Function} options.onClose - 关闭回调
     * @param {boolean} options.closable - 是否可点击遮罩关闭，默认 true
     * @returns {HTMLElement} 返回模态框容器
     */
    show({ title, content, buttons = [], onClose, closable = true }) {
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        
        const modal = document.createElement('div');
        modal.className = 'modal';
        
        // 标题栏
        const header = document.createElement('div');
        header.className = 'modal-header';
        const titleEl = document.createElement('h2');
        titleEl.textContent = title;
        header.appendChild(titleEl);
        
        if (closable) {
            const closeBtn = document.createElement('button');
            closeBtn.className = 'modal-close';
            closeBtn.innerHTML = '✕';
            closeBtn.setAttribute('aria-label', '关闭');
            closeBtn.onclick = () => this.hide(overlay, onClose);
            header.appendChild(closeBtn);
        }
        
        // 内容区
        const body = document.createElement('div');
        body.className = 'modal-body';
        if (typeof content === 'string') {
            body.innerHTML = content;
        } else {
            body.appendChild(content);
        }
        
        // 按钮区
        const footer = document.createElement('div');
        footer.className = 'modal-footer';
        buttons.forEach(btn => {
            const button = document.createElement('button');
            button.className = btn.primary ? 'btn' : 'btn-secondary';
            button.textContent = btn.text;
            button.onclick = () => {
                if (btn.onClick) {
                    const result = btn.onClick();
                    if (result !== false) {
                        this.hide(overlay, onClose);
                    }
                } else {
                    this.hide(overlay, onClose);
                }
            };
            footer.appendChild(button);
        });
        
        modal.appendChild(header);
        modal.appendChild(body);
        if (buttons.length > 0) {
            modal.appendChild(footer);
        }
        
        overlay.appendChild(modal);
        document.body.appendChild(overlay);
        
        // 点击遮罩关闭
        if (closable) {
            overlay.onclick = (e) => {
                if (e.target === overlay) {
                    this.hide(overlay, onClose);
                }
            };
        }
        
        // ESC 键关闭
        const escHandler = (e) => {
            if (e.key === 'Escape' && closable) {
                this.hide(overlay, onClose);
                document.removeEventListener('keydown', escHandler);
            }
        };
        document.addEventListener('keydown', escHandler);
        
        // 触发动画
        requestAnimationFrame(() => {
            overlay.classList.add('modal-show');
        });
        
        return overlay;
    },
    
    /**
     * 隐藏模态框
     * @param {HTMLElement} overlay - Modal.show() 返回的元素
     * @param {Function} onClose - 关闭回调
     */
    hide(overlay, onClose) {
        if (overlay) {
            overlay.classList.remove('modal-show');
            overlay.classList.add('modal-hide');
            setTimeout(() => {
                overlay.remove();
                if (onClose) onClose();
            }, 300);
        }
    },
    
    /**
     * 确认对话框
     * @param {string} message - 确认消息
     * @param {Function} onConfirm - 确认回调
     * @param {Function} onCancel - 取消回调
     */
    confirm(message, onConfirm, onCancel) {
        return this.show({
            title: '确认操作',
            content: `<p>${message}</p>`,
            buttons: [
                { text: '取消', onClick: onCancel },
                { text: '确认', onClick: onConfirm, primary: true }
            ],
            closable: true
        });
    },
    
    /**
     * 提示对话框
     * @param {string} message - 提示消息
     * @param {Function} onOk - 确定回调
     */
    alert(message, onOk) {
        return this.show({
            title: '提示',
            content: `<p>${message}</p>`,
            buttons: [
                { text: '确定', onClick: onOk, primary: true }
            ],
            closable: true
        });
    }
};

// ==================== Skeleton 骨架屏组件 ====================
const Skeleton = {
    /**
     * 创建骨架屏元素
     * @param {string} type - 类型: 'text', 'circle', 'rect'
     * @param {string} width - 宽度（CSS值）
     * @param {string} height - 高度（CSS值）
     * @returns {HTMLElement}
     */
    create(type = 'text', width = '100%', height = '1em') {
        const skeleton = document.createElement('div');
        skeleton.className = `skeleton skeleton-${type}`;
        skeleton.style.width = width;
        skeleton.style.height = height;
        return skeleton;
    },
    
    /**
     * 为容器添加骨架屏
     * @param {HTMLElement} container - 容器元素
     * @param {Array} config - 骨架屏配置 [{type, width, height}]
     */
    fill(container, config) {
        container.innerHTML = '';
        config.forEach(item => {
            const skeleton = this.create(item.type, item.width, item.height);
            container.appendChild(skeleton);
        });
    }
};

// ==================== 工具函数 ====================
const Utils = {
    /**
     * 防抖函数
     * @param {Function} func - 要防抖的函数
     * @param {number} wait - 等待时间（毫秒）
     * @returns {Function}
     */
    debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    },
    
    /**
     * 节流函数
     * @param {Function} func - 要节流的函数
     * @param {number} limit - 时间限制（毫秒）
     * @returns {Function}
     */
    throttle(func, limit) {
        let inThrottle;
        return function(...args) {
            if (!inThrottle) {
                func.apply(this, args);
                inThrottle = true;
                setTimeout(() => inThrottle = false, limit);
            }
        };
    },
    
    /**
     * 复制到剪贴板
     * @param {string} text - 要复制的文本
     * @returns {Promise<boolean>}
     */
    async copyToClipboard(text) {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                await navigator.clipboard.writeText(text);
                return true;
            } else {
                // 降级方案
                const textarea = document.createElement('textarea');
                textarea.value = text;
                textarea.style.position = 'fixed';
                textarea.style.opacity = '0';
                document.body.appendChild(textarea);
                textarea.select();
                const success = document.execCommand('copy');
                document.body.removeChild(textarea);
                return success;
            }
        } catch (err) {
            console.error('复制失败:', err);
            return false;
        }
    },
    
    /**
     * 格式化时间
     * @param {number} seconds - 秒数
     * @returns {string} MM:SS 格式
     */
    formatTime(seconds) {
        const mins = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    },
    
    /**
     * 验证 QQ 号格式
     * @param {string|number} qq - QQ 号
     * @returns {boolean}
     */
    validateQQ(qq) {
        const qqStr = String(qq).trim();
        return /^[1-9]\d{4,10}$/.test(qqStr);
    }
};

// 导出到全局
window.MaterialComponents = {
    Toast,
    Loading,
    Modal,
    Skeleton,
    Utils
};
