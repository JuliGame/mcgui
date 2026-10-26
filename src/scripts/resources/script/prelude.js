// What a page's script sees of a browser, built on the few natives PageScript hands in ([n]). Runs first, in the
// page's own global scope, and returns the four functions PageScript calls: start, frame, key and mouse.
//
// There is a canvas, timers, requestAnimationFrame, keyboard and mouse events, Image, console, performance.now and a
// localStorage that lasts while the page is open. There is no DOM beyond that: getElementById finds a canvas or an
// <img>, and anything else is an element that takes listeners and settings and does nothing with them.
(function (n) {
    var g = this;
    var listeners = {};
    var soon = [];

    function report(e) {
        var where = e && e.fileName ? ' (' + e.fileName + (e.lineNumber ? ':' + e.lineNumber : '') + ')' : '';
        n.log('error', (e && e.stack ? String(e) + where : String(e) + where));
    }

    function safely(fn, self, args) {
        try {
            fn.apply(self, args || []);
        } catch (e) {
            report(e);
        }
    }

    function EventTarget(key) {
        this.__key = key;
    }

    EventTarget.prototype.addEventListener = function (type, fn) {
        if (typeof fn !== 'function' && !(fn && typeof fn.handleEvent === 'function')) return;
        var k = this.__key + ' ' + type;
        var list = listeners[k] || (listeners[k] = []);
        if (list.indexOf(fn) < 0) list.push(fn);
    };
    EventTarget.prototype.removeEventListener = function (type, fn) {
        var list = listeners[this.__key + ' ' + type];
        if (!list) return;
        var i = list.indexOf(fn);
        if (i >= 0) list.splice(i, 1);
    };
    EventTarget.prototype.dispatchEvent = function (ev) {
        fire(this, ev.type, ev);
        return !ev.defaultPrevented;
    };

    function fire(target, type, ev) {
        if (ev.__stopped) return;
        ev.currentTarget = target;
        var list = listeners[target.__key + ' ' + type];
        if (list) {
            list = list.slice();
            for (var i = 0; i < list.length && !ev.__stoppedNow; i++) {
                var l = list[i];
                if (typeof l === 'function') safely(l, target, [ev]);
                else safely(l.handleEvent, l, [ev]);
            }
        }
        var on = target === win ? g['on' + type] : target['on' + type];
        if (typeof on === 'function') safely(on, target, [ev]);
    }

    function event(type, props) {
        var ev = {
            type: type, timeStamp: n.now(), defaultPrevented: false, bubbles: true, cancelable: true,
            preventDefault: function () { this.defaultPrevented = true; },
            stopPropagation: function () { this.__stopped = true; },
            stopImmediatePropagation: function () { this.__stopped = true; this.__stoppedNow = true; }
        };
        for (var k in props) ev[k] = props[k];
        return ev;
    }

    // Fired at [target], then up through the document and the window, as a browser bubbles it.
    function bubble(target, ev) {
        ev.target = target;
        var chain = target === doc ? [doc, win] : [target, doc, win];
        for (var i = 0; i < chain.length; i++) {
            fire(chain[i], ev.type, ev);
            if (ev.__stopped) break;
        }
    }

    // ---------------------------------------------------------------- elements

    function Element(tag, id) {
        EventTarget.call(this, 'el:' + tag + ':' + id + ':' + Math.random());
        this.tagName = this.nodeName = tag.toUpperCase();
        this.id = id || '';
        this.style = {};
        this.dataset = {};
        this.attributes = {};
        this.children = [];
        this.childNodes = [];
        this.className = '';
        var self = this;
        this.classList = {
            add: function () {}, remove: function () {}, toggle: function () { return false; },
            contains: function () { return false; }
        };
    }

    Element.prototype = Object.create(EventTarget.prototype);
    Element.prototype.setAttribute = function (k, v) {
        this.attributes[k] = String(v);
        if (k === 'width' || k === 'height') this[k] = Number(v);
    };
    Element.prototype.getAttribute = function (k) {
        return k in this.attributes ? this.attributes[k] : null;
    };
    Element.prototype.removeAttribute = function (k) { delete this.attributes[k]; };
    Element.prototype.appendChild = function (c) { this.children.push(c); return c; };
    Element.prototype.removeChild = function (c) { return c; };
    Element.prototype.append = Element.prototype.prepend = function () {};
    Element.prototype.remove = function () {};
    Element.prototype.focus = Element.prototype.blur = function () {};
    Element.prototype.querySelector = function () { return null; };
    Element.prototype.querySelectorAll = function () { return []; };
    Element.prototype.getBoundingClientRect = function () {
        return { x: 0, y: 0, left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
    };

    var canvases = [];

    function Canvas(info, index) {
        Element.call(this, 'canvas', info[1]);
        var self = this, handle = info[0], w = info[2], h = info[3], ctx = null;
        this.__canvas = handle;
        this.__index = index;
        Object.defineProperty(this, 'width', {
            get: function () { return w; },
            set: function (v) { w = Math.max(0, v | 0); n.resize(handle, w, h); },
            enumerable: true
        });
        Object.defineProperty(this, 'height', {
            get: function () { return h; },
            set: function (v) { h = Math.max(0, v | 0); n.resize(handle, w, h); },
            enumerable: true
        });
        Object.defineProperty(this, 'clientWidth', { get: function () { var r = rect(self); return r.width; } });
        Object.defineProperty(this, 'clientHeight', { get: function () { var r = rect(self); return r.height; } });
        this.getContext = function (type) {
            if (type !== '2d') return null;
            return ctx || (ctx = n.context(handle, self));
        };
    }

    Canvas.prototype = Object.create(Element.prototype);
    Canvas.prototype.getBoundingClientRect = function () { return rect(this); };
    Canvas.prototype.toDataURL = function () { return 'data:,'; };

    function rect(c) {
        var r = c.__index >= 0 ? n.rect(c.__index) : null;
        if (!r) return { x: 0, y: 0, left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
        return { x: r[0], y: r[1], left: r[0], top: r[1], right: r[0] + r[2], bottom: r[1] + r[3], width: r[2], height: r[3] };
    }

    for (var i = 0; i < n.canvases(); i++) canvases.push(new Canvas(n.canvas(i), i));

    function Image(width, height) {
        Element.call(this, 'img', '');
        var self = this, src = '';
        this.complete = false;
        this.width = this.naturalWidth = width || 0;
        this.height = this.naturalHeight = height || 0;
        Object.defineProperty(this, 'src', {
            get: function () { return src; },
            set: function (v) {
                src = String(v);
                var h = n.image(src);
                self.__image = h;
                self.complete = true;
                if (h) {
                    var size = n.imageSize(h);
                    self.naturalWidth = size[0];
                    self.naturalHeight = size[1];
                    if (!width) self.width = size[0];
                    if (!height) self.height = size[1];
                }
                // Loaded at once, but onload is a browser's to call later: after the script has set it.
                soon.push(function () {
                    var ev = event(h ? 'load' : 'error', {});
                    ev.target = self;
                    fire(self, ev.type, ev);
                });
            },
            enumerable: true
        });
        this.decode = function () { return Promise.resolve(); };
    }

    Image.prototype = Object.create(Element.prototype);

    var stubs = {};

    function byId(id) {
        id = String(id);
        for (var i = 0; i < canvases.length; i++) if (canvases[i].id === id) return canvases[i];
        if (stubs[id]) return stubs[id];
        var src = n.imageSrc(id);
        var el;
        if (src !== null && src !== undefined) {
            el = new Image();
            el.id = id;
            el.src = src;
        } else {
            el = new Element('div', id);
        }
        return stubs[id] = el;
    }

    function select(sel, all) {
        sel = String(sel).trim();
        var out = [];
        if (/^#[\w-]+$/.test(sel)) out = [byId(sel.substring(1))];
        else if (/^canvas(\s*,\s*canvas)*$/i.test(sel)) out = canvases.slice();
        else if (/^canvas#[\w-]+$/i.test(sel)) {
            var c = byId(sel.substring(sel.indexOf('#') + 1));
            out = c instanceof Canvas ? [c] : [];
        }
        return all ? out : (out[0] || null);
    }

    // ---------------------------------------------------------------- document and window

    var doc = new EventTarget('document');
    var win = new EventTarget('window');
    doc.readyState = 'loading';
    doc.body = new Element('body', '');
    doc.documentElement = new Element('html', '');
    doc.head = new Element('head', '');
    doc.hidden = false;
    doc.visibilityState = 'visible';
    doc.getElementById = byId;
    doc.querySelector = function (s) { return select(s, false); };
    doc.querySelectorAll = function (s) { return select(s, true); };
    doc.getElementsByTagName = function (t) { return String(t).toLowerCase() === 'canvas' ? canvases.slice() : []; };
    doc.createElement = function (tag) {
        tag = String(tag).toLowerCase();
        if (tag === 'canvas') {
            var c = new Canvas(n.create(300, 150), -1);
            return c;
        }
        if (tag === 'img') return new Image();
        return new Element(tag, '');
    };
    doc.addEventListener = EventTarget.prototype.addEventListener;
    doc.removeEventListener = EventTarget.prototype.removeEventListener;
    doc.hasFocus = function () { return true; };

    g.document = doc;
    g.window = g.self = g;
    g.addEventListener = function (t, f) { win.addEventListener(t, f); };
    g.removeEventListener = function (t, f) { win.removeEventListener(t, f); };
    g.dispatchEvent = function (ev) { return win.dispatchEvent(ev); };
    g.devicePixelRatio = 1;
    Object.defineProperty(g, 'innerWidth', { get: function () { return n.view()[0]; } });
    Object.defineProperty(g, 'innerHeight', { get: function () { return n.view()[1]; } });
    g.Image = g.HTMLImageElement = Image;
    g.HTMLCanvasElement = Canvas;
    g.HTMLElement = g.Element = Element;
    g.navigator = { userAgent: 'MCGUI', language: 'en', languages: ['en'], maxTouchPoints: 0 };
    g.location = { href: '', hash: '', search: '', pathname: '', reload: function () {} };
    g.performance = { now: function () { return n.now(); } };
    g.alert = g.confirm = g.prompt = function () {};

    var con = {};
    ['log', 'info', 'debug'].forEach(function (k) {
        con[k] = function () { n.log('info', Array.prototype.map.call(arguments, String).join(' ')); };
    });
    ['warn', 'error'].forEach(function (k) {
        con[k] = function () { n.log(k, Array.prototype.map.call(arguments, String).join(' ')); };
    });
    g.console = con;

    var store = {};
    g.localStorage = g.sessionStorage = {
        getItem: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
        setItem: function (k, v) { store[k] = String(v); },
        removeItem: function (k) { delete store[k]; },
        clear: function () { store = {}; },
        key: function (i) { return Object.keys(store)[i] || null; },
        get length() { return Object.keys(store).length; }
    };

    // What only Minecraft has: a command run as the player (as data-command), a sound only they hear, and their name.
    g.mcgui = {
        command: function (c) { n.command(String(c)); },
        sound: function (s) { n.sound(String(s)); },
        get player() { return n.player(); }
    };

    // ---------------------------------------------------------------- time

    var timers = {}, nextTimer = 1, frames = [], nextFrame = 1;

    function addTimer(fn, ms, args, repeat) {
        if (typeof fn !== 'function') return 0;
        var id = nextTimer++;
        ms = Math.max(0, Number(ms) || 0);
        timers[id] = { fn: fn, at: n.now() + ms, every: repeat ? Math.max(1, ms) : 0, args: args };
        return id;
    }

    g.setTimeout = function (fn, ms) { return addTimer(fn, ms, Array.prototype.slice.call(arguments, 2), false); };
    g.setInterval = function (fn, ms) { return addTimer(fn, ms, Array.prototype.slice.call(arguments, 2), true); };
    g.clearTimeout = g.clearInterval = function (id) { delete timers[id]; };
    g.requestAnimationFrame = function (fn) {
        if (typeof fn !== 'function') return 0;
        var id = nextFrame++;
        frames.push({ id: id, fn: fn });
        return id;
    };
    g.cancelAnimationFrame = function (id) {
        for (var i = 0; i < frames.length; i++) if (frames[i].id === id) frames.splice(i, 1);
    };
    g.queueMicrotask = function (fn) { soon.push(fn); };

    function runSoon() {
        while (soon.length) {
            var now = soon;
            soon = [];
            for (var i = 0; i < now.length; i++) safely(now[i], g);
        }
    }

    // ---------------------------------------------------------------- what PageScript calls

    var KEYS = { ' ': 'Space', 'Control': 'ControlLeft' };

    var hovered = null;

    function canvasAt(x, y) {
        for (var i = canvases.length - 1; i >= 0; i--) {
            var r = rect(canvases[i]);
            if (r.width > 0 && x >= r.left && x < r.right && y >= r.top && y < r.bottom) return canvases[i];
        }
        return null;
    }

    function pointerEvents(type) {
        return { mousemove: ['pointermove', 'mousemove'], mousedown: ['pointerdown', 'mousedown'],
            mouseup: ['pointerup', 'mouseup'], click: ['click'], contextmenu: ['contextmenu'] }[type] || [type];
    }

    return {
        start: function () {
            doc.readyState = 'interactive';
            bubble(doc, event('DOMContentLoaded', {}));
            doc.readyState = 'complete';
            fire(win, 'load', event('load', {}));
            runSoon();
        },
        frame: function (now) {
            runSoon();
            for (var id in timers) {
                var t = timers[id];
                if (!t || t.at > now) continue;
                if (t.every) t.at = Math.max(t.at + t.every, now);
                else delete timers[id];
                safely(t.fn, g, t.args);
            }
            var due = frames;
            frames = [];
            for (var i = 0; i < due.length; i++) safely(due[i].fn, g, [now]);
            runSoon();
        },
        key: function (type, key, code) {
            var ev = event(type, { key: key, code: code || KEYS[key] || '', repeat: false, location: 0,
                shiftKey: false, ctrlKey: false, altKey: false, metaKey: false,
                keyCode: key.length === 1 ? key.toUpperCase().charCodeAt(0) : key === 'Control' ? 17 : 0 });
            ev.which = ev.keyCode;
            bubble(doc, ev);
            runSoon();
        },
        mouse: function (type, x, y, button) {
            var c = canvasAt(x, y);
            var target = c || doc;
            if (type === 'mousemove' && hovered !== c) {
                if (hovered) bubble(hovered, event('mouseleave', { clientX: x, clientY: y }));
                if (c) bubble(c, event('mouseenter', { clientX: x, clientY: y }));
                hovered = c;
            }
            var r = c ? rect(c) : { left: 0, top: 0 };
            var types = pointerEvents(type);
            for (var i = 0; i < types.length; i++) {
                var down = type === 'mousedown';
                bubble(target, event(types[i], {
                    clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y, x: x, y: y,
                    offsetX: x - r.left, offsetY: y - r.top, button: button,
                    buttons: down ? (button === 2 ? 2 : 1) : 0, pointerId: 1, pointerType: 'mouse', isPrimary: true,
                    movementX: 0, movementY: 0, shiftKey: false, ctrlKey: false, altKey: false, metaKey: false
                }));
            }
            runSoon();
        }
    };
})
