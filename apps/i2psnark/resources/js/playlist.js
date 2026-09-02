/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

var __i2psnark_isplaying = false;
var __i2psnark_autoplay = false;
var __i2psnark_playindex = -1;
var __i2psnark_playsize = 0;

// note that we use currentTime = 0, not fastSeek(0), because
// Chrome doesn't support it.
// https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement

const setupplaybuttons=()=>{
	let button = document.getElementById('playall');
        if (button === null)
		return;
	const audios = document.getElementsByClassName("audio");
	__i2psnark_playsize = audios.length;
	for (var i = 0; i < __i2psnark_playsize; i++) {
		const audio = audios[i];
		audio.addEventListener("ended", function() {
			audioended();
		});
		audio.addEventListener("pause", function() {
			audiopause();
		});
		audio.addEventListener("play", function() {
			audioplay();
		});
	}
	// all buttons start disabled (controld)
	if (__i2psnark_playsize > 1) {
		button.addEventListener("click", function() {
			playall();
			event.preventDefault();
		});
		button.classList.remove("controld");
		button.classList.add("control");
	}
	button = document.getElementById('playpause');
	button.disabled = true;
	if (__i2psnark_playsize > 1) {
		button.addEventListener("click", function() {
			playpause();
			event.preventDefault();
		});
	}
	button = document.getElementById('playprev');
	if (__i2psnark_playsize > 1) {
		button.disabled = true;
		button.addEventListener("click", function() {
			playprev();
			event.preventDefault();
		});
	}
	button = document.getElementById('playnext');
	if (__i2psnark_playsize > 1) {
		button.addEventListener("click", function() {
			playnext();
			event.preventDefault();
		});
	}
	button = document.getElementById('playresume');
	if (__i2psnark_playsize > 1) {
		button.addEventListener("click", function() {
			playresume();
			event.preventDefault();
		});
	}
}

// buttons

const playprev=()=>{
	let idx = __i2psnark_playindex;
	if (idx <= 0)
		return;
	__i2psnark_playindex--;
	const audios = document.getElementsByClassName("audio");
	if (__i2psnark_isplaying) {
		audios[idx].pause();
		audios[idx].currentTime = 0;
	}
	audios[__i2psnark_playindex].currentTime = 0;
	audios[__i2psnark_playindex].play();
	playing();
}

const playnext=()=>{
	let idx = __i2psnark_playindex;
	if (idx >= __i2psnark_playsize - 1)
		return;
	__i2psnark_playindex++;
	const audios = document.getElementsByClassName("audio");
	if (__i2psnark_isplaying) {
		audios[idx].pause();
		audios[idx].currentTime = 0;
	}
	audios[__i2psnark_playindex].currentTime = 0;
	audios[__i2psnark_playindex].play();
	playing();
}

const playpause=()=>{
	__i2psnark_autoplay = false;
	if (!__i2psnark_isplaying)
		return;
	const audios = document.getElementsByClassName("audio");
	audios[__i2psnark_playindex].pause();
	notplaying();
}

const playall=()=>{
	__i2psnark_autoplay = true;
	if (__i2psnark_isplaying)
		return;
	__i2psnark_playindex = 0;
	const audios = document.getElementsByClassName("audio");
	for (var i = 0; i < __i2psnark_playsize; i++) {
		audios[i].pause();
		audios[i].currentTime = 0;
	}
	audios[0].play();
	playing();
}

const playresume=()=>{
	__i2psnark_autoplay = true;
	if (__i2psnark_isplaying)
		return;
	if (__i2psnark_playindex >= __i2psnark_playsize - 1 || __i2psnark_playindex < 0)
		__i2psnark_playindex = 0;
	const audios = document.getElementsByClassName("audio");
	// start where we were before
	audios[__i2psnark_playindex].play();
	playing();
}

// events

const audioended=()=>{
	//console.warn("audioended");
	const audio = event.target;
	audio.currentTime = 0;
	let span = document.getElementById('playing');
	span.textContent = "";
	__i2psnark_playindex = Number(audio.getAttribute("audioindex"));
	if (__i2psnark_playindex < __i2psnark_playsize - 1) {
		__i2psnark_playindex++;
		if (__i2psnark_autoplay) {
			const audios = document.getElementsByClassName("audio");
			audios[__i2psnark_playindex].play();
			// TODO translate
			span.textContent = "Now playing: " + audio.getAttribute("audioname");
			playing();
		} else {
			notplaying();
		}
	} else {
		__i2psnark_playindex = -1;
		notplaying();
	}
}

const audiopause=()=>{
	const audio = event.target;
	let idx = Number(audio.getAttribute("audioindex"));
	if (idx != __i2psnark_playindex) {
		// ignore unless this is the current one playing
		//console.warn("audiopause ignored");
		return;
	}
	//console.warn("audiopause");
	let span = document.getElementById('playing');
	// TODO translate
	span.textContent = "Paused: " + audio.getAttribute("audioname");
	notplaying();
}

const audioplay=()=>{
	//console.warn("audioplay");
	const audio = event.target;
	__i2psnark_playindex = Number(audio.getAttribute("audioindex"));
	let span = document.getElementById('playing');
	// TODO translate
	span.textContent = "Now playing: " + audio.getAttribute("audioname");
	const audios = document.getElementsByClassName("audio");
	// prevent two at once
	for (var i = 0; i < __i2psnark_playsize; i++) {
		if (i != __i2psnark_playindex) {
			audios[i].pause();
			audios[i].currentTime = 0;
		}
	}
	playing();
}

// state changes

const playing=()=>{
	__i2psnark_isplaying = true;
	let button = document.getElementById('playprev');
	if (__i2psnark_playindex > 0) {
		button.disabled=false;
		button.classList.remove("controld");
		button.classList.add("control");
	} else {
		button.disabled=true;
		button.classList.remove("control");
		button.classList.add("controld");
	}
	button = document.getElementById('playnext');
	if (__i2psnark_playindex < __i2psnark_playsize - 1) {
		button.disabled=false;
		button.classList.remove("controld");
		button.classList.add("control");
	} else {
		button.disabled=true;
		button.classList.remove("control");
		button.classList.add("controld");
	}
	button = document.getElementById('playall');
	button.disabled=true;
	button.classList.remove("control");
	button.classList.add("controld");
	button = document.getElementById('playpause');
	button.disabled=false;
	button.classList.remove("controld");
	button.classList.add("control");
	button = document.getElementById('playresume');
	button.disabled=true;
	button.classList.remove("control");
	button.classList.add("controld");
}

const notplaying=()=>{
	__i2psnark_isplaying = false;
	let button = document.getElementById('playprev');
	if (__i2psnark_playindex > 0) {
		button.disabled=false;
		button.classList.remove("controld");
		button.classList.add("control");
	} else {
		button.disabled=true;
		button.classList.remove("control");
		button.classList.add("controld");
	}
	button = document.getElementById('playnext');
	if (__i2psnark_playindex < __i2psnark_playsize - 1) {
		button.disabled=false;
		button.classList.remove("controld");
		button.classList.add("control");
	} else {
		button.disabled=true;
		button.classList.remove("control");
		button.classList.add("controld");
	}
	button = document.getElementById('playall');
	button.disabled=false;
	button.classList.remove("controld");
	button.classList.add("control");
	button = document.getElementById('playpause');
	button.disabled=true;
	button.classList.remove("control");
	button.classList.add("controld");
	button = document.getElementById('playresume');
	if (__i2psnark_playindex >= 0) {
		button.disabled=false;
		button.classList.remove("controld");
		button.classList.add("control");
	} else {
		button.disabled=true;
		button.classList.remove("control");
		button.classList.add("controld");
	}
}

document.addEventListener("DOMContentLoaded", function() {
    setupplaybuttons();
}, true);

/* @license-end */
