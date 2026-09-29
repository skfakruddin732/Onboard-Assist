import { Component, OnInit, AfterViewChecked, ElementRef, ViewChild, NgZone } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ChatService } from '../../services/chat.service';
import { ChatMessage } from '../../models/chat-response.model';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.component.html',
  styleUrls: ['./chat.component.css']
})
export class ChatComponent implements OnInit, AfterViewChecked {
  @ViewChild('chatMessages') chatMessages!: ElementRef;

  messages: ChatMessage[] = [];
  currentQuestion = '';
  isLoading = false;
  sessionId: string;
  isListening = false;
  speechRecognition: any;

 constructor(
  private router: Router,
  private authService: AuthService,
  private chatService: ChatService,
  private ngZone: NgZone
) {
  this.sessionId = 'session-' + Math.random().toString(36).substr(2, 9);
}

  ngOnInit(): void {
    const state = history.state;
    if (state?.question) {
      this.currentQuestion = state.question;
      this.sendMessage();
    }
    this.initSpeechRecognition();
  }

  ngAfterViewChecked(): void {
    this.scrollToBottom();
  }

  initSpeechRecognition(): void {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRecognition) {
      this.speechRecognition = new SpeechRecognition();
      this.speechRecognition.continuous = false;
      this.speechRecognition.interimResults = false;
      this.speechRecognition.lang = 'en-US';
      this.speechRecognition.onresult = (event: any) => {
  this.ngZone.run(() => {
    this.currentQuestion = event.results[0][0].transcript;
    this.isListening = false;
  });
};
      this.speechRecognition.onerror = () => { this.isListening = false; };
      this.speechRecognition.onend = () => {
  this.ngZone.run(() => {
    this.isListening = false;
  });
};
    }
  }

  toggleVoice(): void {
  if (!this.speechRecognition) {
    alert('Speech Recognition is not supported in this browser.');
    return;
  }

  if (this.isListening) {
    this.speechRecognition.stop();
    this.isListening = false;
  } else {
    this.currentQuestion = '';

    try {
      this.speechRecognition.abort();
    } catch (e) {}

    setTimeout(() => {
      this.speechRecognition.start();
      this.isListening = true;
    }, 100);
  }
}

  sendMessage(): void {
    if (!this.currentQuestion.trim() || this.isLoading) return;

    const userMessage: ChatMessage = {
      type: 'user',
      content: this.currentQuestion,
      timestamp: new Date()
    };
    this.messages.push(userMessage);

    const question = this.currentQuestion;
    this.currentQuestion = '';
    this.isLoading = true;

    this.chatService.ask({ question, sessionId: this.sessionId }).subscribe({
      next: (response) => {
        const aiMessage: ChatMessage = {
          type: 'ai',
          content: response.answer,
          timestamp: new Date()
        };
        this.messages.push(aiMessage);
        this.isLoading = false;
      },
      error: () => {
        const errorMsg: ChatMessage = {
          type: 'ai',
          content: 'Sorry, I encountered an error processing your request. Please try again.',
          timestamp: new Date()
        };
        this.messages.push(errorMsg);
        this.isLoading = false;
      }
    });
  }

  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  scrollToBottom(): void {
    try {
      const el = this.chatMessages?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    } catch(e) {}
  }

  goHome(): void { this.router.navigate(['/home']); }
  logout(): void { this.authService.logout(); }
  navigateTo(path: string): void { this.router.navigate([path]); }

  formatMessage(content: string): string {
    // Convert numbered lists and newlines to proper HTML
    return content
      .replace(/\n/g, '<br>')
      .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
  }
}
